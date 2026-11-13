/* Results page: current poll + history of closed polls. Visible without logging in. */
"use strict";

(function () {
  NV.renderChrome("results");
  let history = [];
  let historyKey = null;

  function renderHistory() {
    const box = NV.$("history");
    NV.$("histCount").textContent = history.length ? "· " + history.length : "";
    box.innerHTML = "";
    if (!history.length) {
      box.innerHTML = '<div class="card empty"><div class="big">📊</div>No closed polls yet. When the administrator closes a poll, its result appears here.</div>';
      return;
    }
    history.forEach((h) => {
      const card = document.createElement("article");
      card.className = "card";
      card.innerHTML = '<h3></h3><div class="meta"></div><div class="results"></div>';
      const title = card.querySelector("h3");
      title.textContent = "#" + h.id + "  " + h.question;
      const tag = document.createElement("span");
      tag.className = "winner-tag";
      tag.textContent = "Winner: " + NV.winnerText(h);
      title.appendChild(tag);
      card.querySelector(".meta").textContent =
        NV.plural(h.total, "vote") + " · created by " + h.createdBy + " · closed " + NV.formatTime(h.closedAt);
      NV.renderBars(card.querySelector(".results"), h, -1);
      box.appendChild(card);
    });
  }

  NetVoteData.subscribe(async (s) => {
    const p = s.poll;
    const st = NV.$("curStatus");
    if (!p) {
      st.textContent = "No poll"; st.className = "pill";
      NV.$("curQuestion").textContent = "No poll is running.";
      NV.$("curMeta").textContent = "";
    } else {
      st.textContent = p.active ? "Open" : "Closed";
      st.className = "pill " + (p.active ? "open" : "closed");
      NV.$("curQuestion").textContent = p.question;
      NV.$("curMeta").textContent = NV.plural(p.total, "vote") + " · started by " + p.createdBy + " · " + NV.formatTime(p.createdAt) +
        (p.total ? " · leading: " + NV.winnerText(p) : "");
    }
    NV.renderBars(NV.$("curBars"), p, s.me ? s.me.voted : -1);

    const h = await NetVoteData.getHistory();
    const key = JSON.stringify(h.map((x) => x.id));
    if (key !== historyKey) { historyKey = key; history = h; renderHistory(); }
  });

  // ---------------------------------------------------------------- CSV download
  NV.$("csvBtn").addEventListener("click", async () => {
    const h = await NetVoteData.getHistory();
    if (!h.length) { NV.toast("There is no history to download yet.", "bad"); return; }
    const q = (v) => '"' + String(v).replace(/"/g, '""') + '"';
    const lines = [["Poll ID", "Question", "Option", "Votes", "Percent", "Total votes", "Created by", "Created at", "Closed at"].map(q).join(",")];
    h.forEach((p) => p.options.forEach((opt, i) => {
      const pct = p.total ? Math.round((100 * p.counts[i]) / p.total) : 0;
      lines.push([p.id, p.question, opt, p.counts[i], pct + "%", p.total, p.createdBy, p.createdAt, p.closedAt].map(q).join(","));
    }));
    const blob = new Blob(["﻿" + lines.join("\r\n")], { type: "text/csv;charset=utf-8" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "netvote_poll_history.csv";
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  });
})();
