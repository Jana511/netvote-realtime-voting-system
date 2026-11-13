/* ==========================================================================
   NetVote – shared page code: header, footer, login guard, charts, toasts.
   Used by every page. Loaded AFTER data.js.
   ========================================================================== */
"use strict";

const NV = (window.NV = {});
NV.$ = (id) => document.getElementById(id);
NV.COLORS = ["#378ADD", "#1D9E75", "#D85A30", "#7F77DD", "#BA7517",
             "#D4537E", "#639922", "#888780", "#E24B4A", "#0F6E56"];

// ---------------------------------------------------------------- header
NV.PAGES = [
  { href: "index.html", label: "Home", key: "home" },
  { href: "vote.html", label: "Vote", key: "vote" },
  { href: "results.html", label: "Results", key: "results" },
  { href: "admin.html", label: "Admin", key: "admin", adminOnly: true },
  { href: "about.html", label: "About", key: "about" },
];

/** Draws the top bar into <header id="site-header"> and the footer into <footer id="site-footer">. */
NV.renderChrome = function (activeKey) {
  const user = NetVoteData.currentUser();
  const header = NV.$("site-header");
  header.className = "topbar";
  header.innerHTML =
    '<div class="topbar-inner collapsed">' +
    '  <a class="brand" href="index.html"><span class="logo">NV</span>' +
    '    <span class="brand-text"><strong>NetVote</strong><small>Real-Time Networked Polling &amp; Voting</small></span></a>' +
    '  <button class="menu-btn" type="button" aria-label="Menu">&#9776;</button>' +
    '  <nav class="nav"></nav>' +
    '  <div class="userbox"></div>' +
    "</div>";

  const nav = header.querySelector(".nav");
  NV.PAGES.forEach((p) => {
    if (p.adminOnly && !(user && user.admin)) return;
    const a = document.createElement("a");
    a.href = p.href;
    a.textContent = p.label;
    if (p.key === activeKey) a.className = "active";
    nav.appendChild(a);
  });

  const box = header.querySelector(".userbox");
  // server status: green when the last request reached the Java backend, red when it failed
  const status = document.createElement("span");
  status.className = "server-status";
  status.innerHTML = '<i class="dot"></i><span>Server</span>';
  const setStatus = (ok) => {
    status.classList.toggle("on", ok === true);
    status.classList.toggle("off", ok === false);
    status.title = ok ? "Connected to the NetVote server" : "Cannot reach the NetVote server";
  };
  window.addEventListener("nv-connection", (e) => setStatus(e.detail));
  setStatus(NetVoteData.fileMode ? false : null);
  box.appendChild(status);
  if (user) {
    const chip = document.createElement("span");
    chip.className = "chip";
    chip.innerHTML = "<b></b>";
    chip.querySelector("b").textContent = user.username;
    if (user.admin) chip.appendChild(document.createTextNode(" · admin"));
    const out = document.createElement("button");
    out.className = "ghost";
    out.type = "button";
    out.textContent = "Log out";
    out.addEventListener("click", async () => {
      await NetVoteData.logout();
      location.href = "login.html";
    });
    box.append(chip, out);
  } else {
    const a = document.createElement("a");
    a.className = "btn light";
    a.href = "login.html";
    a.textContent = "Log in";
    box.appendChild(a);
  }

  const inner = header.querySelector(".topbar-inner");
  header.querySelector(".menu-btn").addEventListener("click", () => {
    nav.classList.toggle("open");
    inner.classList.toggle("collapsed");
    inner.classList.toggle("expanded");
  });

  // opened by double-clicking the file: explain how to start it properly
  if (NetVoteData.fileMode) {
    const warn = document.createElement("div");
    warn.className = "hint server-warning";
    warn.innerHTML = "<b>The NetVote server is not serving this page.</b> Double-clicking the HTML file does not work " +
      "any more, because the data now lives on the Java backend. Start the server (<code>run.bat</code> on Windows, " +
      "<code>./run.sh</code> on Mac/Linux) and then open <a href=\"http://localhost:8080\">http://localhost:8080</a>.";
    const main = document.querySelector("main");
    if (main) main.prepend(warn);
  }

  const footer = NV.$("site-footer");
  if (footer) {
    footer.className = "foot";
    footer.innerHTML = "NetVote – Real-Time Networked Polling &amp; Voting System · EC5205 Network Programming · " +
      '<a href="about.html">About the project</a>';
  }
};

// ---------------------------------------------------------------- login guard
/** Sends the visitor to the login page if not logged in (or not admin when needed). */
NV.requireLogin = function (needAdmin) {
  const user = NetVoteData.currentUser();
  const here = location.pathname.split("/").pop() || "index.html";
  if (!user) {
    location.replace("login.html?next=" + encodeURIComponent(here));
    return null;
  }
  if (needAdmin && !user.admin) {
    location.replace("vote.html?msg=admin");
    return null;
  }
  return user;
};

// ---------------------------------------------------------------- results bars
/**
 * Draws one bar per option into `box`.
 * poll = { options:[..], counts:[..], total }   mark = option index to highlight (or -1)
 */
NV.renderBars = function (box, poll, mark) {
  if (!poll) {
    box.dataset.key = "";
    box.innerHTML = '<p class="muted">No results yet.</p>';
    return;
  }
  // Build the rows only when the poll changes; afterwards just update them,
  // so the bars slide smoothly from the old value to the new one.
  const key = poll.id + ":" + JSON.stringify(poll.options);
  if (box.dataset.key !== key) {
    box.dataset.key = key;
    box.innerHTML = "";
    poll.options.forEach((opt, i) => {
      const row = document.createElement("div");
      row.className = "bar-row";
      row.innerHTML = '<div class="bar-head"><span></span><span></span></div><div class="bar-track"><div class="bar-fill"></div></div>';
      row.querySelector(".bar-fill").style.background = NV.COLORS[i % NV.COLORS.length];
      box.appendChild(row);
    });
    void box.offsetWidth;   // let the browser draw the empty bars first, so they animate in
  }
  const max = Math.max(...poll.counts);
  box.querySelectorAll(".bar-row").forEach((row, i) => {
    const count = poll.counts[i];
    const pct = poll.total ? Math.round((100 * count) / poll.total) : 0;
    row.classList.toggle("winner", poll.total > 0 && count === max);
    const [name, stats] = row.querySelectorAll(".bar-head span");
    name.textContent = poll.options[i] + (mark === i ? "  ✓ your vote" : "");
    stats.textContent = count + (count === 1 ? " vote" : " votes") + " (" + pct + "%)";
    row.querySelector(".bar-fill").style.width = (poll.total ? (100 * count) / poll.total : 0) + "%";
  });
};

/** Names of the leading option(s), e.g. "Java" or "Java & Python (tie)". */
NV.winnerText = function (poll) {
  if (!poll || !poll.total) return "No votes";
  const max = Math.max(...poll.counts);
  const top = poll.options.filter((_, i) => poll.counts[i] === max);
  return top.length === 1 ? top[0] : top.join(" & ") + " (tie)";
};

// ---------------------------------------------------------------- small helpers
NV.toast = function (text, kind) {
  let box = document.querySelector(".toast-box");
  if (!box) {
    box = document.createElement("div");
    box.className = "toast-box";
    document.body.appendChild(box);
  }
  const t = document.createElement("div");
  t.className = "toast" + (kind ? " " + kind : "");
  t.textContent = text;
  box.appendChild(t);
  setTimeout(() => t.remove(), 3500);
};

NV.setMsg = function (el, text, kind) {
  el.textContent = text || "";
  el.className = "msg" + (kind ? " " + kind : "");
};

NV.formatTime = function (iso) {
  if (!iso) return "";
  const d = new Date(iso);
  return d.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" }) +
    " " + d.toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
};

NV.plural = (n, word) => n + " " + word + (n === 1 ? "" : "s");
