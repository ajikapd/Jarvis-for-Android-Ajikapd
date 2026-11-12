#!/usr/bin/env python3
"""Фразы голоса «Джарвис из фильма» из саундпака в assets приложения.

Берёт WAV из «Jarvis Sound Pack от Jarvis Desktop», сводит в моно 22 050 Гц (частота
фирменного голоса, чтобы склеивать без пересчёта), срезает тишину по краям и выравнивает
громкость. Пишет app/src/main/assets/voices/cinema/<id>.wav. Какие фразы в каких ответах
звучат — в CinemaPhrases.kt.

Запуск: python3 tools/gen_cinema_voice.py
"""
import os
import wave

import numpy as np

ROOT = os.path.join(os.path.dirname(__file__), "..")
PACK = os.path.join(ROOT, "Jarvis Sound Pack от Jarvis Desktop")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "voices", "cinema")
RATE = 22050

# id в приложении -> файл в саундпаке. Сюжетные реплики фильма (палладий, Ванко, костюм)
# ответам ассистента не подходят, песню целиком не берём.
CLIPS = {
    "yes_sir": "Да сэр.wav",
    "yes_sir_2": "Да сэр(второй).wav",
    "at_service": "К вашим услугам сэр.wav",
    "always_at_service": "Всегда к вашим услугам сэр.wav",
    "done": "Запрос выполнен сэр.wav",
    "as_you_wish": "Как пожелаете .wav",
    "aye": "Есть.wav",
    "good_morning": "Доброе утро.wav",
    "check_done": "Проверка завершена.wav",
    "loading": "Загружаю сэр.wav",
    "no_more_info": "Другой информации нет.wav",
    "rebooted": "Я перезагрузился сэр.wav",
    "connected": "Мы подключены и готовы.wav",
    "diagnostics": "Начинаю диагностику системы.wav",
    "power_off": "Отключаю питание.wav",
    "build": "Начинаю автоматическую сборку.wav",
    "subtle": "Очень тонкое замечание сэр.wav",
    "congrats": "Поздравляю сэр.wav",
    "what_for": "Чего вы пытаетесь добиться сэр.wav",
    "working": "Мы работаем над проектом сэр 2.wav",
    "battery_2": "Заряд батареи, %/2 %.wav",
    "battery_7": "Заряд батареи, %/7 %.wav",
    "battery_11": "Заряд батареи, %/11 %.wav",
    "battery_13": "Заряд батареи, %/Энергия 13%.wav",
    "battery_15": "Заряд батареи, %/Энергия 15 %.wav",
    "battery_19": "Заряд батареи, %/Сэр, энергия 19 %.wav",
    "battery_48": "Заряд батареи, %/Энергия 48% и падает сэр.wav",
}


def read(path):
    with wave.open(path) as w:
        rate, ch, width = w.getframerate(), w.getnchannels(), w.getsampwidth()
        raw = w.readframes(w.getnframes())
    assert width == 2, path
    pcm = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    if ch > 1:
        pcm = pcm.reshape(-1, ch).mean(axis=1)
    return pcm, rate


def resample(pcm, src, dst):
    if src == dst:
        return pcm
    n = int(round(len(pcm) * dst / src))
    return np.interp(np.linspace(0, len(pcm) - 1, n), np.arange(len(pcm)), pcm).astype(np.float32)


def trim(pcm, rate, floor=0.01, pad_ms=60):
    loud = np.where(np.abs(pcm) > floor)[0]
    if len(loud) == 0:
        return pcm
    pad = int(rate * pad_ms / 1000)
    out = pcm[max(0, loud[0] - pad): loud[-1] + pad]
    fade = min(len(out) // 4, int(rate * 0.01))
    if fade:
        out[:fade] *= np.linspace(0, 1, fade)
        out[-fade:] *= np.linspace(1, 0, fade)
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for cid, name in CLIPS.items():
        pcm, rate = read(os.path.join(PACK, name))
        pcm = trim(resample(pcm, rate, RATE), RATE)
        peak = np.abs(pcm).max() or 1.0
        pcm = pcm * (0.89 / peak)  # около −1 дБ, как у синтеза
        data = (np.clip(pcm, -1, 1) * 32767).astype("<i2").tobytes()
        path = os.path.join(OUT, cid + ".wav")
        with wave.open(path, "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(RATE)
            w.writeframes(data)
        total += os.path.getsize(path)
        print(f"{cid:18} {len(pcm) / RATE:4.1f} с  {name}")
    print(f"итого {total // 1024} КБ в {OUT}")


if __name__ == "__main__":
    main()
