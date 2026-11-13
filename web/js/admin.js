/* Administrator page: create poll, monitor it, close it. */
"use strict";

(function () {
  const me = NV.requireLogin(true);
  if (!me) return;
  NV.renderChrome("admin");

  const MAX_OPTIONS = 10, MIN_OPTIONS = 2;
  let pollActive = false;

  // ---------------------------------------------------------------- option editor
  function addOptionLine(value) {
    const box = NV.$("optEditor");
    if (box.children.length >= MAX_OPTIONS) return;
    const line = document.createElement("div");
    line.className = "opt-line";
    line.innerHTML = '<span class="num"></span><input type="text" maxlength="80"><button type="button" class="ghost" title="Remove">✕</button>';
    line.querySelector("input").value = value || "";
    line.querySelector("button").addEventListener("click", () => { line.remove(); renumber(); });
    box.appendChild(line);
    renumber();
    return line.querySelector("input");
  }
  function renumber() {
    const lines = NV.$("optEditor").querySelectorAll(".opt-line");
    lines.forEach((l, i) => {
      l.querySelector(".num").textContent = i + 1 + ".";
      l.querySelector("input").placeholder = "Option " + (i + 1);
      l.querySelector("button").disabled = lines.length <= MIN_OPTIONS;
    });
    NV.$("addOpt").disabled = lines.length >= MAX_OPTIONS;
  }
  function resetEditor() {
    NV.$("optEditor").innerHTML = "";
    NV.$("newQuestion").value = "";
    for (let i = 0; i < 3; i++) addOptionLine("");
  }
  NV.$("addOpt").addEventListener("click", () => { const i = addOptionLine(""); if (i) i.focus(); });
  resetEditor();

  // ---------------------------------------------------------------- actions
  NV.$("pollForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const options = [...NV.$("optEditor").querySelectorAll("input")].map((i) => i.value);
    try {
      await NetVoteData.createPoll(NV.$("newQuestion").value, options);
      NV.setMsg(NV.$("createMsg"), "Poll started. Voters can vote now.", "ok");
      NV.toast("Poll started.", "ok");
      resetEditor();
    } catch (err) {
      NV.setMsg(NV.$("createMsg"), err.message, "bad");
    }
  });

  NV.$("closeBtn").addEventListener("click", async () => {
    if (!confirm("Close the poll? Nobody will be able to vote after this.")) return;
    try {
      await NetVoteData.closePoll();
      NV.setMsg(NV.$("closeMsg"), "Poll closed. The result was saved to the history.", "ok");
      NV.toast("Poll closed.", "ok");
    } catch (err) {
      NV.setMsg(NV.$("closeMsg"), err.message, "bad");
    }
  });

  NV.$("demoVotes").addEventListener("click", async () => {
    try {
      const r = await NetVoteData.addDemoVotes(5);
      NV.setMsg(NV.$("demoMsg"), "Added " + NV.plural(r.added, "demo vote") + ".", "ok");
    } catch (err) {
      NV.setMsg(NV.$("demoMsg"), err.message, "bad");
    }
  });

  NV.$("resetAll").addEventListener("click", async () => {
    if (!confirm("Delete the current poll and the whole history from the server?")) return;
    try {
      await NetVoteData.resetAll();
      NV.setMsg(NV.$("demoMsg"), "All polls were deleted.", "ok");
      NV.setMsg(NV.$("closeMsg"), "");
    } catch (err) {
      NV.setMsg(NV.$("demoMsg"), err.message, "bad");
    }
  });

  // ---------------------------------------------------------------- live view
  NetVoteData.subscribe((s) => {
    const p = s.poll;
    pollActive = !!(p && p.active);
    const status = NV.$("pollStatus");
    if (!p) {
      status.textContent = "No poll";
      status.className = "pill";
      NV.$("question").textContent = "No poll has been created yet.";
    } else {
      status.textContent = p.active ? "Open for voting" : "Closed";
      status.className = "pill " + (p.active ? "open" : "closed");
      NV.$("question").textContent = p.question;
    }
    NV.$("statVotes").textContent = p ? p.total : 0;
    NV.$("statUsers").textContent = s.users.length;
    NV.$("statLeader").textContent = p && p.total ? NV.winnerText(p) : "–";
    NV.renderBars(NV.$("results"), p, s.me ? s.me.voted : -1);

    const voters = NV.$("voters");
    voters.innerHTML = "";
    const names = p ? p.voters : [];
    if (!names.length) voters.innerHTML = "<span>nobody yet</span>";
    names.forEach((n) => {
      const sp = document.createElement("span");
      sp.textContent = n;
      voters.appendChild(sp);
    });

    NV.$("closeBtn").disabled = !pollActive;
    NV.$("startBtn").disabled = pollActive;
    NV.$("demoVotes").disabled = !pollActive;
    if (pollActive && !NV.$("createMsg").textContent)
      NV.setMsg(NV.$("createMsg"), "Close the current poll before starting a new one.");
    else if (!pollActive && NV.$("createMsg").textContent === "Close the current poll before starting a new one.")
      NV.setMsg(NV.$("createMsg"), "");
  });
})();
