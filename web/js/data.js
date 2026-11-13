/* ==========================================================================
   NetVote – DATA LAYER (talks to the Java backend)

   Every page gets and changes data ONLY through window.NetVoteData.
   Each function sends an HTTP request to the Java server (src/backend) and
   returns a Promise. The server stores everything and checks every rule;
   this file never decides anything by itself.

     login        POST /api/login         logout   POST /api/logout
     getState     GET  /api/state         vote     POST /api/vote
     getHistory   GET  /api/history       createPoll POST /api/polls
     closePoll    POST /api/polls/close   addDemoVotes POST /api/demo-votes
     resetAll     POST /api/reset

   Live updates: subscribe() asks the server for the state every 2 seconds and
   calls the page again when something changed. (In the networking phase this
   can be replaced by the server pushing updates instantly.)

   The "state" object every page receives:
   {
     poll:  null | { id, question, options:[..], counts:[..], total, active,
                     createdBy, createdAt, closedAt, voters:[names, admin only] },
     users: ["kasun", "nimali", ...],              // online now
     me:    null | { username, admin, voted }      // voted = option index or -1
   }
   ========================================================================== */
"use strict";

(function () {
  const REFRESH_MS = 2000;
  const SESSION_KEY = "nv_session";   // { token, username, admin } – per browser tab
  const FILE_MODE = location.protocol === "file:";
  const FILE_MSG = "This page must be opened through the NetVote server. Start the server and open http://localhost:8080";

  // ---------------------------------------------------------------- login kept per tab
  let memorySession = null;           // used if sessionStorage is blocked
  function getSession() {
    try { return JSON.parse(sessionStorage.getItem(SESSION_KEY)); } catch (e) { return memorySession; }
  }
  function setSession(s) {
    memorySession = s;
    try { s ? sessionStorage.setItem(SESSION_KEY, JSON.stringify(s)) : sessionStorage.removeItem(SESSION_KEY); } catch (e) {}
  }

  // ---------------------------------------------------------------- connection status
  let online = null;
  function setOnline(v) {
    if (v === online) return;
    online = v;
    window.dispatchEvent(new CustomEvent("nv-connection", { detail: v }));
  }

  /** The login is no longer valid (e.g. the server was restarted). */
  function sessionEnded() {
    setSession(null);
    const page = location.pathname.split("/").pop();
    if (page === "vote.html" || page === "admin.html") location.replace("login.html?expired=1&next=" + page);
    else location.reload();
  }

  // ---------------------------------------------------------------- one HTTP request
  async function api(method, path, params) {
    if (FILE_MODE) throw new Error(FILE_MSG);
    const options = { method, headers: {} };
    const s = getSession();
    if (s) options.headers["Authorization"] = "Bearer " + s.token;
    if (params) options.body = new URLSearchParams(params);   // form data: a=1&b=2
    let res;
    try {
      res = await fetch(path, options);
    } catch (e) {
      setOnline(false);
      throw new Error("Cannot reach the NetVote server. Is it running?");
    }
    setOnline(true);
    const data = await res.json().catch(() => ({ error: "Unexpected reply from the server." }));
    if (!res.ok) {
      const err = new Error(data.error || "Request failed (" + res.status + ")");
      err.status = res.status;
      if (res.status === 401 && s && path !== "/api/login") sessionEnded();
      throw err;
    }
    return data;
  }

  // ---------------------------------------------------------------- live updates
  const listeners = new Set();
  let lastJson = null, lastState = null, timer = null;

  async function refresh() {
    let s;
    try {
      s = await api("GET", "/api/state");
    } catch (e) {
      return lastState;          // server down: keep showing the last data
    }
    if (getSession() && !s.me) { sessionEnded(); return s; }   // server forgot this login
    const json = JSON.stringify(s);
    if (json !== lastJson) {
      lastJson = json;
      lastState = s;
      listeners.forEach((cb) => { try { cb(s); } catch (e) { console.error(e); } });
    }
    return s;
  }

  // ======================================================================
  //                         PUBLIC FUNCTIONS
  // ======================================================================
  window.NetVoteData = {
    mode: "server",
    fileMode: FILE_MODE,

    /** The user logged in in this tab, or null. (No request needed.) */
    currentUser() {
      const s = getSession();
      return s ? { username: s.username, admin: s.admin } : null;
    },

    async login(username, password) {
      const r = await api("POST", "/api/login", { username: username || "", password: password || "" });
      setSession({ token: r.token, username: r.username, admin: r.admin });
      return { username: r.username, admin: r.admin };
    },

    async logout() {
      try { await api("POST", "/api/logout", {}); } catch (e) { /* log out locally anyway */ }
      setSession(null);
    },

    async getState() {
      return (await refresh()) || api("GET", "/api/state");
    },

    /** Calls callback(state) now and every time something changes. Returns a function to stop. */
    subscribe(callback) {
      listeners.add(callback);
      if (lastState) setTimeout(() => callback(lastState), 0);
      else refresh();
      if (!timer && !FILE_MODE) timer = setInterval(refresh, REFRESH_MS);
      return () => listeners.delete(callback);
    },

    async createPoll(question, options) {
      const r = await api("POST", "/api/polls", { question: question || "", options: (options || []).join("\n") });
      refresh();
      return r;
    },

    async vote(optionIndex) {
      const r = await api("POST", "/api/vote", { option: optionIndex });
      refresh();
      return r;
    },

    async closePoll() {
      const r = await api("POST", "/api/polls/close", {});
      refresh();
      return r;
    },

    async getHistory() {
      return api("GET", "/api/history");
    },

    // ---------------- testing helpers (administrator only) ----------------
    async addDemoVotes(count) {
      const r = await api("POST", "/api/demo-votes", { count: count || 5 });
      refresh();
      return r;
    },

    async resetAll() {
      const r = await api("POST", "/api/reset", {});
      refresh();
      return r;
    },
  };
})();
