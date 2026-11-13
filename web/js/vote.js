/* Voting page: shows the current poll, lets the user vote, shows live results and online users. */
"use strict";

(function () {
  const me = NV.requireLogin(false);
  if (!me) return;
  NV.renderChrome("vote");
  NV.$("whoText").textContent = "Logged in as " + me.username + (me.admin ? " (administrator)" : "");
  if (new URLSearchParams(location.search).get("msg") === "admin") NV.$("adminNote").classList.remove("hidden");

  let shownPollId = null;   // which poll the radio buttons were built for
  let lastActive = null;
  let firstUpdate = true;

  function buildOptions(options) {
    const box = NV.$("options");
    box.innerHTML = "";
    options.forEach((opt, i) => {
      const lbl = document.createElement("label");
      lbl.className = "option";
      const input = document.createElement("input");
      input.type = "radio";
      input.name = "choice";
      input.value = i;
      const span = document.createElement("span");
      span.textContent = opt;
      lbl.append(input, span);
      box.appendChild(lbl);
    });
  }

  function render(s) {
    const p = s.poll;
    const voted = s.me ? s.me.voted : -1;

    // notifications when something changes while the page is open
    if (!firstUpdate && p) {
      if (p.id !== shownPollId && p.active) NV.toast("A new poll has started!", "ok");
      else if (p.id === shownPollId && lastActive && !p.active) NV.toast("The poll has been closed.");
    }
    firstUpdate = false;
    lastActive = p ? p.active : null;

    // ---- poll + options
    const status = NV.$("pollStatus");
    NV.$("noPoll").classList.toggle("hidden", !!p);
    NV.$("voteForm").classList.toggle("hidden", !p);
    if (!p) {
      status.textContent = "No poll";
      status.className = "pill";
      NV.$("question").textContent = "No poll yet.";
      shownPollId = null;
      NV.setMsg(NV.$("voteMsg"), "");
    } else {
      status.textContent = p.active ? "Open for voting" : "Closed";
      status.className = "pill " + (p.active ? "open" : "closed");
      NV.$("question").textContent = p.question;
      if (shownPollId !== p.id) {
        buildOptions(p.options);
        shownPollId = p.id;
        NV.setMsg(NV.$("voteMsg"), "");
      }
    }
    const canVote = !!p && p.active && voted < 0;
    document.querySelectorAll("#options .option").forEach((lbl, i) => {
      const input = lbl.querySelector("input");
      input.disabled = !canVote;
      if (voted === i) input.checked = true;
      lbl.classList.toggle("mine", voted === i);
    });
    NV.$("voteBtn").disabled = !canVote;
    if (p && voted >= 0) NV.setMsg(NV.$("voteMsg"), "Your vote for “" + p.options[voted] + "” was recorded.", "ok");
    else if (p && !p.active) NV.setMsg(NV.$("voteMsg"), "Voting is closed. Final result: " + NV.winnerText(p) + ".");

    // ---- results
    NV.renderBars(NV.$("results"), p, voted);
    NV.$("totalVotes").textContent = p ? "· " + NV.plural(p.total, "vote") : "";

    // ---- users
    const ul = NV.$("users");
    ul.innerHTML = "";
    s.users.forEach((u) => {
      const li = document.createElement("li");
      li.textContent = u;
      if (u === me.username) {
        li.className = "me";
        const tag = document.createElement("span");
        tag.className = "tag";
        tag.textContent = "you";
        li.appendChild(tag);
      }
      ul.appendChild(li);
    });
    NV.$("userCount").textContent = "· " + s.users.length;
  }

  NV.$("voteForm").addEventListener("submit", async (e) => {
    e.preventDefault();
    const chosen = document.querySelector('#options input[name="choice"]:checked');
    if (!chosen) { NV.setMsg(NV.$("voteMsg"), "Select an option first.", "bad"); return; }
    NV.$("voteBtn").disabled = true;
    try {
      await NetVoteData.vote(Number(chosen.value));
      NV.toast("Vote submitted.", "ok");
    } catch (err) {
      NV.setMsg(NV.$("voteMsg"), err.message, "bad");
      NetVoteData.getState().then(render);
    }
  });

  NetVoteData.subscribe(render);
})();
