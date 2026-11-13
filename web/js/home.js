/* Home page: shows a live preview of the current poll. */
"use strict";

NV.renderChrome("home");

const user = NetVoteData.currentUser();
if (user) {
  NV.$("heroMain").textContent = "Go to the poll";
  NV.$("heroMain").href = "vote.html";
  NV.$("ctaBtn").textContent = user.admin ? "Open admin panel" : "Go to the poll";
  NV.$("ctaBtn").href = user.admin ? "admin.html" : "vote.html";
}

NetVoteData.subscribe((s) => {
  const p = s.poll;
  if (!p) {
    NV.$("liveLabel").textContent = "Current poll";
    NV.$("liveQ").textContent = "No poll is running right now.";
    NV.$("liveBars").innerHTML = "";
    NV.$("liveMeta").textContent = NV.plural(s.users.length, "user") + " online";
    return;
  }
  NV.$("liveLabel").textContent = p.active ? "● Live now" : "Last poll (closed)";
  NV.$("liveQ").textContent = p.question;
  NV.renderBars(NV.$("liveBars"), p, -1);
  NV.$("liveMeta").textContent = NV.plural(p.total, "vote") + " · " + NV.plural(s.users.length, "user") + " online";
});
