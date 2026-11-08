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

  /* ---------- Вкладки экранов и переключатель тем ---------- */
  document.querySelectorAll(".tabs [data-tab]").forEach(btn => btn.addEventListener("click", () => {
    document.querySelectorAll(".tabs [data-tab]").forEach(b => b.setAttribute("aria-selected", String(b === btn)));
    document.querySelectorAll("[data-panel]").forEach(p => { p.hidden = p.dataset.panel !== btn.dataset.tab; });
  }));
  const themePhones = document.querySelectorAll("#themePhones img");
  const parts = ["home", "skills", "live"];
  document.querySelectorAll(".themes [data-theme]").forEach(btn => btn.addEventListener("click", () => {
    document.querySelectorAll(".themes [data-theme]").forEach(b => b.setAttribute("aria-pressed", String(b === btn)));
    themePhones.forEach((img, i) => {
      img.classList.add("swap");
      const next = `media/theme-${btn.dataset.theme}-${parts[i]}.webp`;
      const pre = new Image();
      pre.onload = () => { img.src = next; img.classList.remove("swap"); };
      pre.src = next;
    });
  }));
  // Предзагрузка тем, чтобы переключение было мгновенным
  setTimeout(() => ["gold", "mark", "stealth", "light"].forEach(t => parts.forEach(p => { new Image().src = `media/theme-${t}-${p}.webp`; })), 3000);

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
