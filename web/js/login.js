/* Login page */
"use strict";

NV.renderChrome("login");

let adminMode = false;
const params = new URLSearchParams(location.search);
const already = NetVoteData.currentUser();
if (already) location.replace(already.admin ? "admin.html" : "vote.html");

function setRole(admin) {
  adminMode = admin;
  NV.$("tabVoter").classList.toggle("on", !admin);
  NV.$("tabAdmin").classList.toggle("on", admin);
  NV.$("pwRow").classList.toggle("hidden", !admin);
  NV.$("loginBtn").textContent = admin ? "Connect as administrator" : "Connect as voter";
  NV.$("loginError").textContent = "";
}
NV.$("tabVoter").addEventListener("click", () => setRole(false));
NV.$("tabAdmin").addEventListener("click", () => setRole(true));
if (params.get("role") === "admin") setRole(true);
if (params.get("expired")) NV.$("loginError").textContent = "Your session has ended (the server may have restarted). Please log in again.";

NV.$("loginForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const err = NV.$("loginError");
  err.textContent = "";
  const username = NV.$("username").value.trim();
  const password = adminMode ? NV.$("password").value : "";
  if (!username) { err.textContent = "Enter a username."; return; }
  if (adminMode && !password) { err.textContent = "Enter the administrator password."; return; }
  NV.$("loginBtn").disabled = true;
  try {
    const u = await NetVoteData.login(username, password);
    const next = params.get("next");
    const safeNext = next && /^[a-z]+\.html$/.test(next) && next !== "login.html" ? next : null;
    location.href = safeNext || (u.admin ? "admin.html" : "vote.html");
  } catch (ex) {
    err.textContent = ex.message;
    NV.$("loginBtn").disabled = false;
  }
});

NV.$("username").focus();
