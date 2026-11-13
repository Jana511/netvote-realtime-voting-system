# NetVote – Real-Time Networked Polling & Voting System
EC5205 Network Programming project – **all networking code is Java** (JDK only, no libraries, no database).

- **Java TCP server** (`ServerSocket`, port **5000**) – one thread per client, own text protocol, heartbeat
- **Java desktop client** (Swing + `Socket`) – connects over TCP
- **Java HTTP server** (JDK built-in, port **8080**) – serves the website, REST API and live push (Server-Sent Events)
- Browsers and desktop clients share **one poll**: a vote from either appears on every screen at once

Step-by-step explanation of the networking code, tests and viva points: **`NETWORKING_GUIDE.md`**.

---

## 1. Software needed

| Software | Needed? | Notes |
|---|---|---|
| **Java JDK 21** (Eclipse Temurin) | **Yes** | `javac -version` must show 21 (or 11+) |
| **VS Code** | **Yes** | editor |
| **Extension Pack for Java** (VS Code extension, by Microsoft) | **Yes** | compiles/runs Java, Run & Debug buttons |
| Chrome or Edge | **Yes** | to open the website |
| Telnet Client (Windows feature) | For testing | talk to the TCP server by hand |
| Wireshark (+ Npcap) | Optional | screenshots of TCP packets for the report |

**Not needed:** Maven, Gradle, Spring, Tomcat, Node.js, a database, Live Server, Eclipse.

---

## 2. Open in VS Code
1. Extract the zip (right-click → Extract All).
2. VS Code → **File → Open Folder…** → choose the **NetVote** folder (the one containing `src`, `web`, `run.bat`).
3. Click **Yes, I trust the authors**.
4. Wait until the status bar stops showing *Java: Loading…* / *Building* (first time ~1 minute).
5. The **Problems** panel (Ctrl+Shift+M) should show no errors.

## 3. Run
**Server:** Run and Debug (Ctrl+Shift+D) → choose **"1. NetVote Server (web 8080, TCP 5000)"** → ▶.
(If 8080 is used by another program, choose **"1b. … (web 8081 …)"** and use port 8081 below.)
The terminal shows `NetVote server started` and the addresses. Open **http://localhost:8080**.

**Desktop client:** choose **"2. NetVote Desktop Client"** → ▶ (press ▶ again for more windows).
Server IP `localhost`, port `5000`, any username → Connect.

**Without the Run buttons** (VS Code terminal, in the NetVote folder):
```
.\run.bat              (server; or  .\run.bat 8081  if 8080 is busy)
.\run-client.bat       (desktop client – run in a second terminal, as many as you like)
```

**Login:** any username (2–20 letters/digits/_). Administrator: password **`admin123`**.
**Stop the server:** click in its terminal and press Ctrl+C (or the red ■ in the debug toolbar).

---

## 4. Check that the networking works

**Test 1 – the server is listening on both ports.** In Command Prompt:
```
netstat -ano | findstr ":5000 :8080"
```
Two lines with `LISTENING` = TCP server (5000) and HTTP server (8080) are up.

**Test 2 – talk to the TCP server by hand (raw socket).**
```
telnet localhost 5000
```
You get `WELCOME|NetVote server ready. Please log in.` Then type (press Enter after each):
```
LOGIN|tester|
SUBMIT_VOTE|0
SUBMIT_VOTE|1
CLOSE_POLL
```
Expected: `LOGIN_OK|tester|0`, `POLL|…`, `RESULT_UPDATE|…`, `USERS|…`; after the vote `VOTED|0`,
a new `RESULT_UPDATE|…` (pushed to everyone) and `VOTE_ACCEPTED|0`; then
`ERROR|You have already voted in this poll.` and `ERROR|Only the administrator can do this.`
(Start a poll in the browser first, so there is something to vote for. Close telnet: Ctrl+] then `quit`.)

**Test 3 – real-time between desktop and web.**
Browser: log in as admin → start a poll. Desktop client: log in as `kasun` → vote.
The browser bars move **immediately** without refreshing; the desktop window shows browser votes too.
Every connection, login and vote is printed in the server terminal with `[TCP]` for desktop clients.

**Test 4 – a client crashes.** Close a desktop window (or end `javaw.exe` in Task Manager):
the user disappears from every online list; the server and other clients keep working.

**Test 5 – two computers (LAN).** Same Wi-Fi or phone hotspot. The server prints
`Other devices on the same Wi-Fi: website http://192.168.x.x:8080   desktop client 192.168.x.x port 5000`.
On the other laptop/phone open that website address, or run the desktop client with that IP.
Allow Java through Windows Firewall when asked (tick *Private networks*). Details: `NETWORKING_GUIDE.md`, Step 7.

**Test 6 – Wireshark (evidence for the report).** Capture with filter `tcp.port == 5000`,
right-click a packet → Follow → TCP Stream: you see the `LOGIN|…`, `SUBMIT_VOTE|…`, `RESULT_UPDATE|…` lines.
(On one PC use the *Adapter for loopback traffic capture*.)

---

## 5. Common problems
| Problem | Fix |
|---|---|
| `javac` / Java not found in VS Code | close and reopen VS Code after installing the JDK; check `javac -version` in its terminal |
| "Port 8080 is already in use", or the page shows `"No static resource"` | another server uses 8080 – use config **1b** (8081) or stop the other program |
| "Port 5000 is already in use" | an older NetVote server is still running – stop it (Ctrl+C / Task Manager → `java.exe`) |
| Run buttons missing | install *Extension Pack for Java*, open the **NetVote** folder itself (not a parent folder) |
| Page shows a yellow warning | open it via http://localhost:8080, not by double-clicking an HTML file |
| Other devices cannot connect | same network, correct IP, firewall allowed, Wi-Fi set to *Private*; try a phone hotspot |

---

## 6. Project structure
```
src/common/MessageProtocol.java      TCP text protocol (COMMAND|arg|arg…) – shared by server and client
src/network/TcpServer.java           ServerSocket :5000, accept loop, thread pool
src/network/ClientHandler.java       one thread per desktop client: read commands, push updates, heartbeat timeout
src/client/VoteClient.java           client Socket, listener thread, PING every 20 s
src/client/ClientGUI.java            Swing desktop window (voter + administrator)
src/backend/Main.java                starts HTTP :8080 + TCP :5000, prints LAN addresses
src/backend/EventBus.java            "something changed" → notify every connected client
src/backend/EventStreamHandler.java  /api/events – Server-Sent Events push to browsers
src/backend/ApiHandler.java          REST API (JSON) for the website
src/backend/StaticFileHandler.java   serves web/
src/backend/PollService.java         voting rules (synchronized), saves after each change
src/backend/SessionManager.java      logins for web + desktop, who is online
src/backend/Storage.java  Poll.java  Json.java  ApiException.java
web/                                 website (6 pages); web/js/data.js talks to the Java server
data/polls.txt                       created automatically – every poll and vote
run.bat / run-client.bat             start server / desktop client from a terminal (Windows)
.vscode/launch.json                  the Run & Debug entries
```

**TCP protocol (port 5000):** `LOGIN|user|pw`, `SUBMIT_VOTE|i`, `CREATE_POLL|q|a|b…`, `CLOSE_POLL`,
`GET_POLL`, `PING`, `QUIT` → `WELCOME`, `LOGIN_OK`, `POLL`, `NO_POLL`, `RESULT_UPDATE`, `VOTE_ACCEPTED`,
`VOTED`, `POLL_CLOSED`, `USERS`, `ERROR`, `PONG`.
