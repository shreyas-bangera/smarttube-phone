/* StreamTube website interactions */
(function () {
  "use strict";

  const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  /* ---------- footer year ---------- */
  const year = document.getElementById("year");
  if (year) year.textContent = new Date().getFullYear();

  /* ---------- scroll progress + nav state ---------- */
  const progress = document.getElementById("progress");
  const nav = document.getElementById("nav");

  function onScroll() {
    const doc = document.documentElement;
    const max = doc.scrollHeight - doc.clientHeight;
    const pct = max > 0 ? (doc.scrollTop / max) * 100 : 0;
    if (progress) progress.style.width = pct + "%";
    if (nav) nav.classList.toggle("scrolled", doc.scrollTop > 24);
  }
  document.addEventListener("scroll", onScroll, { passive: true });
  onScroll();

  /* ---------- mobile nav ---------- */
  const burger = document.getElementById("burger");
  const navMobile = document.getElementById("navMobile");
  if (burger && navMobile) {
    burger.addEventListener("click", () => {
      const open = navMobile.classList.toggle("open");
      burger.classList.toggle("open", open);
      burger.setAttribute("aria-expanded", String(open));
    });
    navMobile.addEventListener("click", (e) => {
      if (e.target.tagName === "A") {
        navMobile.classList.remove("open");
        burger.classList.remove("open");
        burger.setAttribute("aria-expanded", "false");
      }
    });
  }

  /* ---------- reveal on scroll (with stagger) ---------- */
  document.querySelectorAll("[data-stagger]").forEach((group) => {
    group.querySelectorAll(".reveal").forEach((el, i) => {
      if (!el.style.getPropertyValue("--d")) el.style.setProperty("--d", (i * 0.08).toFixed(2) + "s");
    });
  });

  const io = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          entry.target.classList.add("in");
          io.unobserve(entry.target);
        }
      });
    },
    { threshold: 0.12, rootMargin: "0px 0px -40px 0px" }
  );
  document.querySelectorAll(".reveal").forEach((el) => io.observe(el));

  /* ---------- screenshot gallery arrows ---------- */
  const shots = document.getElementById("shots");
  const prev = document.getElementById("shotPrev");
  const next = document.getElementById("shotNext");
  if (shots && prev && next) {
    /* An endless, gently moving gallery: the screenshots twice in a row, and on reaching the second set it steps
       back by one set (no visible jump). It pauses while hovered, touched or focused, and when off screen. */
    const originals = Array.from(shots.children);
    originals.forEach((node) => {
      const copy = node.cloneNode(true);
      copy.setAttribute("aria-hidden", "true");
      shots.appendChild(copy);
    });
    shots.style.scrollSnapType = "none";
    const half = () => shots.scrollWidth / 2;
    const wrap = () => {
      if (shots.scrollLeft >= half()) shots.scrollLeft -= half();
      else if (shots.scrollLeft <= 0) shots.scrollLeft += half();
    };
    let pos = 0;
    let paused = false;
    let resumeAt = 0;
    let onScreen = false;
    let last = 0;
    const SPEED = 0.04; // px per ms: a slow drift
    const hold = (ms) => { resumeAt = performance.now() + ms; };
    const frame = (t) => {
      const dt = last ? Math.min(48, t - last) : 0;
      last = t;
      if (!reduceMotion && onScreen && !paused && t >= resumeAt) {
        pos += SPEED * dt;
        if (pos >= half()) pos -= half();
        shots.scrollLeft = Math.round(pos);
      } else {
        pos = shots.scrollLeft; // carry on from wherever it was moved to
      }
      requestAnimationFrame(frame);
    };
    new IntersectionObserver((entries) => { onScreen = entries[0].isIntersecting; }, { threshold: 0.1 }).observe(shots);
    shots.addEventListener("pointerenter", () => { paused = true; });
    shots.addEventListener("pointerleave", () => { paused = false; hold(400); });
    shots.addEventListener("touchstart", () => hold(3000), { passive: true });
    shots.addEventListener("focusin", () => { paused = true; });
    shots.addEventListener("focusout", () => { paused = false; });
    shots.addEventListener("scroll", wrap, { passive: true });
    const step = () => Math.min(shots.clientWidth * 0.8, 560);
    prev.addEventListener("click", () => { hold(2500); wrap(); shots.scrollBy({ left: -step(), behavior: "smooth" }); });
    next.addEventListener("click", () => { hold(2500); wrap(); shots.scrollBy({ left: step(), behavior: "smooth" }); });
    requestAnimationFrame(frame);
  }

  /* ---------- hero phone tilt (desktop, motion allowed) ---------- */
  const heroVisual = document.getElementById("heroVisual");
  const tilt = document.getElementById("phoneTilt");
  if (heroVisual && tilt && !reduceMotion && window.matchMedia("(hover: hover)").matches) {
    heroVisual.addEventListener("mousemove", (e) => {
      const r = heroVisual.getBoundingClientRect();
      const x = (e.clientX - r.left) / r.width - 0.5;
      const y = (e.clientY - r.top) / r.height - 0.5;
      tilt.style.setProperty("--mx", x.toFixed(3));
      tilt.style.setProperty("--my", y.toFixed(3));
    });
    heroVisual.addEventListener("mouseleave", () => {
      tilt.style.setProperty("--mx", "0");
      tilt.style.setProperty("--my", "0");
    });
  }

  /* ---------- feature card spotlight follows cursor ---------- */
  if (!reduceMotion) {
    document.querySelectorAll(".card").forEach((card) => {
      card.addEventListener("mousemove", (e) => {
        const r = card.getBoundingClientRect();
        card.style.setProperty("--cx", ((e.clientX - r.left) / r.width) * 100 + "%");
        card.style.setProperty("--cy", ((e.clientY - r.top) / r.height) * 100 + "%");
      });
    });
  }

  /* ---------- FAQ: close others when one opens ---------- */
  const faqs = document.querySelectorAll("details.faq");
  faqs.forEach((d) => {
    d.addEventListener("toggle", () => {
      if (d.open) faqs.forEach((other) => { if (other !== d) other.open = false; });
    });
  });
})();
