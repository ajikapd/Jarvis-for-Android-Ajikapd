(() => {
  "use strict";
  const reduced = matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ---------- Язык ---------- */
  const ru = {};
  document.querySelectorAll("[data-i18n]").forEach(el => { ru[el.dataset.i18n] = el.textContent; });
  const titles = { ru: document.title, en: "Jarvis — voice assistant for Android" };
  function setLang(lang) {
    const dict = lang === "en" ? window.I18N.en : ru;
    document.querySelectorAll("[data-i18n]").forEach(el => {
      const v = dict[el.dataset.i18n];
      if (v) el.textContent = v;
    });
    document.querySelectorAll("[data-lang-block]").forEach(el => { el.hidden = el.dataset.langBlock !== lang; });
    document.documentElement.lang = lang;
    document.title = titles[lang];
    document.querySelectorAll(".lang button").forEach(b => b.setAttribute("aria-pressed", String(b.dataset.lang === lang)));
    try { localStorage.setItem("lang", lang); } catch (_) { /* приватный режим */ }
  }
  document.querySelectorAll(".lang button").forEach(b => b.addEventListener("click", () => setLang(b.dataset.lang)));
  let saved = null;
  try { saved = localStorage.getItem("lang"); } catch (_) { /* приватный режим */ }
  const initial = saved || (navigator.language || "ru").toLowerCase().startsWith("ru") ? (saved || "ru") : "en";
  if (initial !== "ru") setLang(initial);

  /* ---------- Появление и счётчики ---------- */
  function count(el) {
    const target = Number(el.dataset.count), suffix = el.dataset.suffix || "";
    if (reduced || target === 0) { el.textContent = target + suffix; return; }
    const t0 = performance.now(), dur = 1600;
    const step = now => {
      const p = Math.min(1, (now - t0) / dur), e = 1 - Math.pow(1 - p, 3);
      el.textContent = Math.round(target * e).toLocaleString("ru-RU") + suffix;
      if (p < 1) requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  }
  const io = new IntersectionObserver(entries => {
    entries.forEach(e => {
      if (!e.isIntersecting) return;
      e.target.classList.add("in");
      e.target.querySelectorAll("[data-count]").forEach(count);
      io.unobserve(e.target);
    });
  }, { threshold: .15 });
  document.querySelectorAll(".reveal").forEach((el, i) => {
    el.style.transitionDelay = (i % 4) * 80 + "ms";
    io.observe(el);
  });

  /* ---------- Наклон карточек ---------- */
  if (!reduced && matchMedia("(hover: hover)").matches) {
    document.querySelectorAll(".tilt").forEach(card => {
      card.addEventListener("pointermove", ev => {
        const r = card.getBoundingClientRect();
        const x = (ev.clientX - r.left) / r.width, y = (ev.clientY - r.top) / r.height;
        card.style.setProperty("--mx", x * 100 + "%");
        card.style.setProperty("--my", y * 100 + "%");
        card.style.transitionDelay = "0ms";
        card.style.transform = `rotateX(${(0.5 - y) * 12}deg) rotateY(${(x - 0.5) * 14}deg) translateZ(10px)`;
      });
      card.addEventListener("pointerleave", () => { card.style.transform = ""; });
    });
  }

  /* ---------- Фон тускнеет под текстом ---------- */
  const backdrop = [document.getElementById("scene"), document.querySelector(".css-reactor")];
  const dim = () => {
    const o = 1 - Math.min(1, scrollY / innerHeight) * .7;
    backdrop.forEach(el => {
      if (!el) return;
      // На телефоне запасной реактор стоит за текстом, поэтому он тусклее
      const base = el.classList.contains("css-reactor") && innerWidth <= 900 ? .55 : 1;
      el.style.opacity = base * o;
    });
  };
  addEventListener("scroll", dim, { passive: true });
  dim();

})();
