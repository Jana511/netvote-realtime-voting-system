# NetVote – Networking Part: Step-by-Step Guide

The website and backend were already working. This phase adds the **networking**:

| | Before | After |
|---|---|---|
| Desktop app | – | Java Swing client over a **TCP socket** (port 5000) |
| Updates in browsers | page asked the server every 2 s | server **pushes** every change instantly (Server-Sent Events) |
| Who can join | browser tabs on one computer | laptops and phones on the **same Wi-Fi / LAN** |
| Online users | web only | web **and** desktop, one shared list |

```
 Desktop client ──TCP 5000──┐                              ┌─ data/polls.txt
 Desktop client ──TCP 5000──┤   ┌────────────────────────┐ │
                            ├──►│  NetVote server (Java) │─┤
 Laptop browser ─HTTP 8080──┤   │  PollService (1 poll)  │ │
 Phone browser ──HTTP 8080──┘   │  EventBus → push to all│ └─ console log
                                └────────────────────────┘
```

New / changed files:
```
src/common/MessageProtocol.java     NEW  Step 1 – the TCP message format
src/backend/EventBus.java           NEW  Step 2 – "something changed" notifications
src/backend/PollService.java        CHG  Step 2 – publishes every change, Snapshot for TCP
src/backend/SessionManager.java     CHG  Step 2 – desktop + web users, live connections
src/network/TcpServer.java          NEW  Step 3 – ServerSocket, accept loop, thread pool
src/network/ClientHandler.java      NEW  Step 4 – one thread per desktop client
src/client/VoteClient.java          NEW  Step 5 – client Socket, listener thread, heartbeat
src/client/ClientGUI.java           NEW  Step 5 – Swing window
src/backend/EventStreamHandler.java NEW  Step 6 – live push to browsers (/api/events)
web/js/data.js                      CHG  Step 6 – EventSource instead of 2-second refresh
src/backend/Main.java               CHG  Step 7 – starts HTTP + TCP, prints LAN addresses
run-client.bat / run-client.sh      NEW  start a desktop client
```

Compile everything (from the NetVote folder):
```
javac -encoding UTF-8 -d out src/common/*.java src/backend/*.java src/network/*.java src/client/*.java
```
(Windows: same with `\` instead of `/`. Or just double-click `run.bat`.)

---

## Step 1 – Design the message protocol  (`src/common/MessageProtocol.java`)

**Problem:** TCP is a *stream* of bytes. If a client sends two messages quickly, the server
may receive them glued together, or one message split in two. TCP has no idea where one
message ends.

**Our solution – a text protocol:**
- One message = **one line** ending with `\n`. Java's `println()` adds it, `readLine()` reads up to it.
- Fields separated by `|`:  `COMMAND|arg1|arg2|…`
- If a question itself contains `|` or a newline, it is **escaped** (`\p`, `\n`, `\\`), so it can never break a message.

```java
encode("SUBMIT_VOTE", 2)        →  "SUBMIT_VOTE|2"
decode("POLL|1|Best day?|1|Mon|Wed")  →  ["POLL","1","Best day?","1","Mon","Wed"]
```

| Client → Server | Meaning |
|---|---|
| `LOGIN|kasun|` / `LOGIN|admin|admin123` | log in (password only for the admin) |
| `SUBMIT_VOTE|1` | vote for option 1 (counting starts at 0) |
| `CREATE_POLL|Question?|A|B|C` · `CLOSE_POLL` | admin only |
| `GET_POLL` · `PING` · `QUIT` · `HELLO` | resend everything · heartbeat · leave · test |

| Server → Client | Meaning |
|---|---|
| `WELCOME|…` · `LOGIN_OK|kasun|0` | connected · logged in (1 = admin) |
| `POLL|id|question|1|opt1|opt2…` · `NO_POLL` | the current poll (1 = open, 0 = closed) |
| `RESULT_UPDATE|id|total|c1|c2…` | live counts |
| `VOTE_ACCEPTED|i` · `VOTED|i` | your vote was counted / you voted earlier |
| `POLL_CLOSED|id` · `USERS|a|b|c` | poll ended · who is online (desktop + web) |
| `ERROR|message` · `PONG` | request refused · heartbeat answer |

Both sides use this one class, so they always agree on the format.

---

## Step 2 – Let the server announce changes  (`EventBus`, `PollService`, `SessionManager`)

Real-time means: **when anything changes, the server tells every client at once.**

- `EventBus` keeps a list of *listeners* (one per connected desktop client and one per open web page).
- `PollService` calls `bus.publish()` after every change: new poll, vote, poll closed, reset.
- `SessionManager` calls it when a user logs in, logs out, connects or disconnects.
- `publish()` returns immediately. The listeners are called on **one separate thread**
  (`event-dispatcher`), so the thread that handled a vote is never slowed down by sending updates.
  Many changes at the same moment are merged into one notification.

`SessionManager` now serves both kinds of users. A user is **online** while they have a live
connection (an open TCP socket or an open web page). The same username can never be logged in
twice – not even once on the web and once on the desktop.

`PollService` stays `synchronized`: 50 clients voting in the same millisecond are counted one
after another, so no vote is lost or counted twice (tested: 50 simultaneous votes → exactly 50).

---

## Step 3 – The TCP server  (`src/network/TcpServer.java`)

```java
serverSocket = new ServerSocket(5000);          // listen on port 5000
while (true) {
    Socket socket = serverSocket.accept();      // WAIT until a client connects
    socket.setTcpNoDelay(true);                 // send small messages immediately
    pool.execute(new ClientHandler(socket, …)); // serve it on its own thread
}
```
- `accept()` **blocks**, so it runs on its own thread (`tcp-accept`).
- `Executors.newCachedThreadPool()` gives **one thread per connected client**. A slow or
  crashed client only affects its own thread.

**Test after this step:** start the server and connect with a raw TCP tool:
```
telnet localhost 5000        (Windows: enable "Telnet Client" in Windows Features)
ncat localhost 5000          (if Nmap is installed)
```
You see `WELCOME|NetVote server ready. Please log in.` Type `LOGIN|test|` and press Enter.

---

## Step 4 – One thread per client  (`src/network/ClientHandler.java`)

**Reading (the client's thread):**
```java
while ((line = in.readLine()) != null) {   // null = client closed the connection
    handle(decode(line));
}
```
`handle()` checks the command, calls `PollService` / `SessionManager` (the **same** code the
website uses – same rules), and answers `ERROR|message` if something is not allowed.

**Writing (pushed updates):** after `LOGIN` the handler subscribes to the `EventBus`. When it
is notified, `sendUpdates()` compares the current state with what this client was **already
sent** and sends only the difference: a new `POLL`, a `RESULT_UPDATE`, `POLL_CLOSED`, `USERS`.

**Failures never stop the server:**
- Client closes the window → `QUIT` or `readLine()` returns `null`.
- Cable pulled / Wi-Fi lost / app crashed → `IOException`, or no message at all.
  The client sends `PING` every 20 s. `socket.setSoTimeout(90_000)` closes a client that
  stays silent for 90 s.
- In every case the `finally` block unsubscribes, logs the user out, closes the socket, and
  everyone else's user list updates.

**Test after this step (telnet):**
```
LOGIN|kasun|                    → LOGIN_OK|kasun|0, then POLL…, RESULT_UPDATE…, USERS…
SUBMIT_VOTE|0                   → VOTE_ACCEPTED|0   (the browser bars move at the same time)
SUBMIT_VOTE|1                   → ERROR|You have already voted in this poll.
CLOSE_POLL                      → ERROR|Only the administrator can do this.
```

---

## Step 5 – The desktop client  (`src/client/VoteClient.java`, `ClientGUI.java`)

`VoteClient` = the network part:
```java
socket = new Socket();
socket.connect(new InetSocketAddress(host, 5000), 5000);   // 5-second timeout
```
- A **listener thread** reads server lines forever and passes them to the GUI.
- A **heartbeat thread** sends `PING` every 20 s.
- `send()` writes one protocol line.

`ClientGUI` = the Swing window: login screen (server IP, port, username, admin password),
then the poll with radio buttons, a live bar chart, the online-user list, and the admin panel.
Server messages arrive on the listener thread, but Swing must only be changed on its own
thread, so every update goes through `SwingUtilities.invokeLater(...)`. The window never
freezes, even if the network is slow.

**Test after this step:** start the server (`run.bat`), then `run-client.bat` two or three
times. Log in as different users and vote. Every window and every browser updates at once.

---

## Step 6 – Instant updates for browsers  (`EventStreamHandler.java`, `web/js/data.js`)

Before, each page asked `GET /api/state` every 2 seconds (**polling**). Now:

1. The page opens one connection: `new EventSource("/api/events?token=…")`.
2. The server answers `200 OK` with `Content-Type: text/event-stream` and **keeps the response
   open** (`sendResponseHeaders(200, 0)`).
3. Every time the `EventBus` publishes, the server writes into that open response:
   ```
   event: state
   data: {"poll":{…},"users":[…],"me":{…}}

   ```
4. The browser receives it immediately and redraws the page. Measured: a desktop vote appears
   in the browser about **25 ms** later.
5. `: ping` every 5 s detects closed tabs. If the connection drops, the browser reconnects by
   itself after 2 s (`retry: 2000`).

Why Server-Sent Events and not WebSockets? The JDK's built-in HTTP server supports SSE without
any library, and updates only need to go server → browser (actions still use normal POST requests).

---

## Step 7 – Run it on several computers (LAN)

1. **Same network:** connect every laptop/phone to the **same Wi-Fi or mobile hotspot**.
   (University Wi-Fi often blocks device-to-device traffic. If nothing connects, use a phone hotspot.)
2. **Start the server** on one laptop (`run.bat`). It prints its addresses:
   ```
   Other devices on the same Wi-Fi:  website http://192.168.1.20:8080   desktop client 192.168.1.20 port 5000
   ```
   (Or find it with `ipconfig` → "IPv4 Address".)
3. **Firewall:** when Windows asks *"Allow Java Platform SE binary to communicate on these
   networks?"*, tick **Private networks** and click **Allow**. If you missed it, run this in
   Command Prompt **as administrator**:
   ```
   netsh advfirewall firewall add rule name="NetVote" dir=in action=allow protocol=TCP localport=8080,5000
   ```
   Also make sure the Wi-Fi is set to a **Private** network (Settings → Network → Wi-Fi → network properties).
4. **Phones / other laptops (browser):** open `http://192.168.1.20:8080`.
5. **Other laptops (desktop app):** copy the NetVote folder, run `run-client.bat`,
   type Server IP `192.168.1.20`, Port `5000`.

---

## Step 8 – Test and collect evidence (screenshots for the report)

| # | Test | Expected |
|---|---|---|
| 1 | Server + 2 desktop clients + 2 browsers (one a phone) | all appear in every "online users" list |
| 2 | Admin starts a poll in the browser | appears in desktop windows at once |
| 3 | Vote from a desktop client | browser bars move instantly (no refresh) |
| 4 | Vote twice (desktop or web) | "You have already voted in this poll." |
| 5 | Same username on desktop and web | "That username is already connected." |
| 6 | Voter sends `CLOSE_POLL` (telnet) | `ERROR|Only the administrator can do this.` |
| 7 | Admin closes the poll from the desktop app | every page shows "Closed"; voting refused |
| 8 | Close a desktop window / kill it in Task Manager | user leaves every list; server keeps running |
| 9 | Close a browser tab | user leaves the list within about 20 s (short grace time so a page refresh does not count as leaving) |
| 10 | Stop and restart the server | poll and votes restored from `data/polls.txt` |
| 11 | Two different laptops on one hotspot | Step 7 works across machines |

**Wireshark (strong evidence of networking):** capture on the Wi-Fi adapter when two laptops are used
(for `localhost` on one Windows PC, capture on *Adapter for loopback traffic capture*) and use the filter
`tcp.port == 5000`. Right-click a packet → *Follow → TCP Stream* to show the readable
`LOGIN|…`, `SUBMIT_VOTE|…`, `RESULT_UPDATE|…` lines and the TCP three-way handshake (SYN, SYN-ACK, ACK).
Filter `tcp.port == 8080` and follow the `/api/events` stream to show the pushed `event: state` messages.

The server console logs every connection, login, vote and disconnect with a time stamp – screenshot it too.

---

## Points to explain in the report / viva

- **Client–server model:** only the server stores and counts votes. Clients just send requests,
  so a client cannot fake results.
- **Why TCP (not UDP):** votes must arrive exactly once and in order. TCP gives reliable, ordered
  delivery and tells us when a connection breaks.
- **Sockets:** `ServerSocket` listens and `accept()`s; each client gets its own `Socket` with an
  input stream (read lines) and an output stream (write lines).
- **Concurrency:** a thread per client, a thread pool, `synchronized` methods and
  `ConcurrentHashMap` prevent lost or double-counted votes (50 simultaneous votes tested).
- **Message framing:** newline-terminated lines plus escaping, because TCP has no message boundaries.
- **Push vs polling:** the server pushes changes (TCP messages, Server-Sent Events) instead of
  clients asking repeatedly. This means less traffic and instant updates.
- **Fault tolerance:** heartbeat (`PING`/`PONG`) + read timeout, `finally` clean-up, automatic
  browser reconnect, and data saved to a file after every change.
- **Two protocols, one system:** the TCP text protocol for the desktop app and HTTP/SSE for
  browsers share the same `PollService` and `SessionManager`, so the rules are identical.
- **Limitations (be honest):** traffic is not encrypted (no TLS), voters have no password, and
  it is meant for a LAN, not the internet. Possible improvements: `SSLServerSocket` / HTTPS,
  per-user passwords, a countdown timer, several polls at the same time.
