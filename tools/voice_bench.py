#!/usr/bin/env python3
"""Сравнение моделей узнавания голоса на записях с телефона.

1. Снять записи:   adb exec-out run-as com.jarvis.assistant tar c files/voice_clips | tar x -C <папка>
2. По желанию разметить: <папка>/files/voice_clips/labels.tsv, строки «имя_файла<TAB>owner|other».
   Без разметки сравнение невозможно: эталон владельца — образцы голоса (enroll_*), остальное надо пометить.
3. Запуск: python3 tools/voice_bench.py <папка>/files/voice_clips <папка_с_моделями>

Для каждой модели из папки моделей (*.onnx): эталон владельца — средний отпечаток образцов,
дальше оценка каждой записи и разрыв между владельцем и чужими: чем он больше и чем меньше
пересечение, тем лучше модель. Нужен пакет sherpa-onnx (pip install sherpa-onnx numpy).
"""
import os
import sys
import time
import wave

import numpy as np
import sherpa_onnx

RATE = 16000


def read_wav(path):
    with wave.open(path) as w:
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0


def speech(pcm, frame=480, ratio=0.12, min_rms=0.0008):
    """Как VoiceTrim в приложении: только звучащие кадры."""
    n = len(pcm) // frame
    if n == 0:
        return pcm
    frames = pcm[: n * frame].reshape(n, frame)
    rms = np.sqrt((frames ** 2).mean(axis=1))
    keep = rms >= max(min_rms, rms.max() * ratio)
    out = frames[keep].reshape(-1)
    return out if len(out) >= 3200 else pcm


def windows(pcm, size=24000, hop=12000, limit=6):
    if len(pcm) <= size:
        return [pcm]
    return [pcm[i : i + size] for i in range(0, len(pcm) - size + 1, hop)][:limit]


class Model:
    def __init__(self, path):
        cfg = sherpa_onnx.SpeakerEmbeddingExtractorConfig(model=path, num_threads=2)
        self.ex = sherpa_onnx.SpeakerEmbeddingExtractor(cfg)
        self.ms = []

    def embed(self, pcm):
        s = self.ex.create_stream()
        s.accept_waveform(RATE, pcm)
        s.input_finished()
        t = time.perf_counter()
        e = np.array(self.ex.compute(s), dtype=np.float32)
        self.ms.append((time.perf_counter() - t) * 1000)
        return e / (np.linalg.norm(e) + 1e-9)


def load_index(clips):
    rows = []
    with open(os.path.join(clips, "index.tsv"), encoding="utf-8") as f:
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) >= 2 and os.path.exists(os.path.join(clips, p[0])):
                rows.append({"file": p[0], "kind": p[1], "sec": float(p[2] or 0),
                             "cam": float(p[3]) if len(p) > 3 and p[3] else None,
                             "note": p[4] if len(p) > 4 else ""})
    return rows


def load_labels(clips):
    path = os.path.join(clips, "labels.tsv")
    labels = {}
    if os.path.exists(path):
        for line in open(path, encoding="utf-8"):
            p = line.split()
            if len(p) >= 2:
                labels[p[0]] = p[1]
    return labels


def label_of(row, labels):
    if row["file"] in labels:
        return labels[row["file"]]
    if row["kind"].startswith("enroll"):
        return "enroll"
    # «media=true» значит лишь, что играл звук: владелец тоже говорит поверх видео.
    return "?"


def stats(name, owner, other):
    if not owner or not other:
        return f"{name}: мало размеченных записей (владелец {len(owner)}, чужие {len(other)})"
    owner, other = np.array(owner), np.array(other)
    # Равная ошибка: порог, при котором доля отказов владельцу равна доле пропущенных чужих.
    best = min(
        (((owner < t).mean(), (other >= t).mean(), t) for t in np.linspace(-0.2, 1.0, 241)),
        key=lambda x: abs(x[0] - x[1]),
    )
    return (f"{name}: владелец {np.median(owner):.2f} (мин {owner.min():.2f}), "
            f"чужие {np.median(other):.2f} (макс {other.max():.2f}), "
            f"равная ошибка {100 * (best[0] + best[1]) / 2:.1f}% при пороге {best[2]:.2f}")


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    clips, models_dir = sys.argv[1], sys.argv[2]
    rows = load_index(clips)
    labels = load_labels(clips)
    enroll = [r for r in rows if r["kind"].startswith("enroll")]
    tests = [r for r in rows if not r["kind"].startswith("enroll")]
    print(f"записей: {len(rows)}, образцов голоса: {len(enroll)}, разметка: {len(labels)}")
    if not enroll:
        print("Нет образцов голоса: перезапишите голос в «Ещё → Только мой голос».")
        sys.exit(1)

    audio = {r["file"]: speech(read_wav(os.path.join(clips, r["file"]))) for r in rows}
    models = sorted(f for f in os.listdir(models_dir) if f.endswith(".onnx"))
    for mf in models:
        m = Model(os.path.join(models_dir, mf))
        ref = np.mean([m.embed(w) for r in enroll for w in windows(audio[r["file"]])], axis=0)
        ref /= np.linalg.norm(ref) + 1e-9
        groups = {}
        for r in tests:
            score = max(float(np.dot(ref, m.embed(w))) for w in windows(audio[r["file"]]))
            lab = label_of(r, labels)
            groups.setdefault((lab, r["kind"].split("_")[0]), []).append(score)
            groups.setdefault((lab, "all"), []).append(score)
        print(f"\n== {mf}  ({np.median(m.ms):.0f} мс на кусок на этом ПК)")
        for (lab, kind), v in sorted(groups.items()):
            print(f"  {lab:7} {kind:8} n={len(v):3}  медиана {np.median(v):.2f}  мин {min(v):.2f}  макс {max(v):.2f}")
        owner = groups.get(("owner", "all"), [])
        print("  " + stats("итог", owner, groups.get(("other", "all"), [])))

    cam = [r["cam"] for r in tests if r["cam"] is not None and label_of(r, labels) == "other"]
    if cam:
        print(f"\nдля сравнения: оценки CAM++ в телефоне на чужих записях, макс {max(cam):.2f}")


if __name__ == "__main__":
    main()
