package client;

import static common.MessageProtocol.*;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Swing user interface for both voters and the administrator. */
public class ClientGUI extends JFrame implements VoteClient.Listener {
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);
    private VoteClient client;
    private boolean loggedIn, isAdmin;

    // login screen
    private final JTextField hostField = new JTextField("localhost", 16);
    private final JTextField portField = new JTextField(String.valueOf(DEFAULT_PORT), 16);
    private final JTextField userField = new JTextField(16);
    private final JCheckBox adminBox = new JCheckBox("Log in as administrator");
    private final JPasswordField passField = new JPasswordField(16);
    private final JButton connectBtn = new JButton("Connect");

    // main screen
    private final JLabel whoLabel = new JLabel(" ");
    private final JLabel questionLabel = new JLabel("No poll yet. Waiting for the administrator...");
    private final JPanel optionsPanel = new JPanel();
    private final List<JRadioButton> radios = new ArrayList<>();
    private ButtonGroup group = new ButtonGroup();
    private final JButton submitBtn = new JButton("Submit Vote");
    private final JLabel statusLabel = new JLabel(" ");
    private final ResultsPanel results = new ResultsPanel();
    private final DefaultListModel<String> usersModel = new DefaultListModel<>();
    private final JPanel adminPanel = new JPanel(new BorderLayout(6, 6));
    private final JTextField newQuestion = new JTextField();
    private final JTextArea newOptions = new JTextArea(4, 20);
    private final JButton startBtn = new JButton("Start Poll");
    private final JButton closeBtn = new JButton("Close Poll");

    // current poll state
    private List<String> options = new ArrayList<>();
    private boolean pollActive;
    private int votedIndex = -1;

    public ClientGUI() {
        super("NetVote - Desktop Client");
        // closing the window first tells the server we are leaving (QUIT), then exits
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                if (client != null) client.close();
                dispose();
                System.exit(0);
            }
        });
        root.add(buildLogin(), "login");
        root.add(buildMain(), "main");
        add(root);
        setSize(880, 560);
        setLocationRelativeTo(null);
    }

    // ------------------------------------------------------------ UI building
    private JPanel buildLogin() {
        JPanel p = new JPanel(new GridBagLayout());
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(5, 5, 5, 5);
        c.anchor = GridBagConstraints.WEST;
        JLabel title = new JLabel("NetVote");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; form.add(title, c);
        c.gridwidth = 1;
        addRow(form, c, 1, "Server IP:", hostField);
        addRow(form, c, 2, "Port:", portField);
        addRow(form, c, 3, "Username:", userField);
        c.gridx = 0; c.gridy = 4; c.gridwidth = 2; form.add(adminBox, c);
        c.gridwidth = 1;
        addRow(form, c, 5, "Admin password:", passField);
        passField.setEnabled(false);
        adminBox.addActionListener(e -> passField.setEnabled(adminBox.isSelected()));
        c.gridx = 0; c.gridy = 6; c.gridwidth = 2; c.fill = GridBagConstraints.HORIZONTAL; form.add(connectBtn, c);
        connectBtn.addActionListener(e -> connect());
        getRootPane().setDefaultButton(connectBtn);
        p.add(form);
        return p;
    }

    private void addRow(JPanel p, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0; c.gridy = row; p.add(new JLabel(label), c);
        c.gridx = 1; p.add(field, c);
    }

    private JPanel buildMain() {
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        whoLabel.setFont(whoLabel.getFont().deriveFont(Font.BOLD));
        JButton logoutBtn = new JButton("Log out");
        logoutBtn.addActionListener(e -> resetToLogin());
        JPanel top = new JPanel(new BorderLayout());
        top.add(whoLabel, BorderLayout.CENTER);
        top.add(logoutBtn, BorderLayout.EAST);
        p.add(top, BorderLayout.NORTH);

        // left: question + options + submit
        JPanel left = new JPanel(new BorderLayout(6, 6));
        questionLabel.setFont(questionLabel.getFont().deriveFont(Font.BOLD, 16f));
        optionsPanel.setLayout(new BoxLayout(optionsPanel, BoxLayout.Y_AXIS));
        JPanel south = new JPanel(new GridLayout(2, 1, 4, 4));
        submitBtn.setEnabled(false);
        submitBtn.addActionListener(e -> submitVote());
        south.add(submitBtn);
        south.add(statusLabel);
        left.add(questionLabel, BorderLayout.NORTH);
        left.add(new JScrollPane(optionsPanel), BorderLayout.CENTER);
        left.add(south, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(280, 300));

        // right: connected users
        JList<String> usersList = new JList<>(usersModel);
        JScrollPane usersScroll = new JScrollPane(usersList);
        usersScroll.setBorder(BorderFactory.createTitledBorder("Connected users"));
        usersScroll.setPreferredSize(new Dimension(150, 300));

        results.setBorder(BorderFactory.createTitledBorder("Live results"));
        p.add(left, BorderLayout.WEST);
        p.add(results, BorderLayout.CENTER);
        p.add(usersScroll, BorderLayout.EAST);

        // admin panel (only visible to the administrator)
        JPanel fields = new JPanel(new BorderLayout(4, 4));
        fields.add(labeled("Question:", newQuestion), BorderLayout.NORTH);
        fields.add(labeled("Options (one per line):", new JScrollPane(newOptions)), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new GridLayout(2, 1, 4, 4));
        buttons.add(startBtn);
        buttons.add(closeBtn);
        adminPanel.setBorder(BorderFactory.createTitledBorder("Administrator"));
        adminPanel.add(fields, BorderLayout.CENTER);
        adminPanel.add(buttons, BorderLayout.EAST);
        adminPanel.setVisible(false);
        startBtn.addActionListener(e -> startPoll());
        closeBtn.addActionListener(e -> client.send(CLOSE_POLL));
        p.add(adminPanel, BorderLayout.SOUTH);
        return p;
    }

    private JPanel labeled(String text, JComponent c) {
        JPanel p = new JPanel(new BorderLayout(2, 2));
        p.add(new JLabel(text), BorderLayout.NORTH);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    // ------------------------------------------------------------ actions
    private void connect() {
        String user = userField.getText().trim();
        if (user.isEmpty()) { JOptionPane.showMessageDialog(this, "Enter a username."); return; }
        int port;
        try { port = Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException e) { JOptionPane.showMessageDialog(this, "Port must be a number."); return; }
        String host = hostField.getText().trim();
        String pw = adminBox.isSelected() ? new String(passField.getPassword()) : "";
        connectBtn.setEnabled(false);
        new Thread(() -> {
            VoteClient c = new VoteClient(this);
            try {
                c.connect(host, port);
                client = c;
                c.startListening();
                c.send(LOGIN, user, pw);
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    connectBtn.setEnabled(true);
                    JOptionPane.showMessageDialog(this, "Could not connect to " + host + ":" + port + "\n" + e.getMessage(),
                            "Connection failed", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    private void submitVote() {
        for (int i = 0; i < radios.size(); i++) {
            if (radios.get(i).isSelected()) {
                submitBtn.setEnabled(false);          // re-enabled only if the server rejects it
                client.send(SUBMIT_VOTE, i);
                return;
            }
        }
        statusLabel.setText("Select an option first.");
    }

    private void startPoll() {
        List<Object> args = new ArrayList<>();
        args.add(newQuestion.getText());
        for (String line : newOptions.getText().split("\\R")) if (!line.isBlank()) args.add(line.trim());
        client.send(CREATE_POLL, args.toArray());
    }

    // ------------------------------------------------------------ server messages
    @Override
    public void onMessage(String[] m) {
        SwingUtilities.invokeLater(() -> handle(m));
    }

    private void handle(String[] m) {
        switch (m[0]) {
            case LOGIN_OK:
                loggedIn = true;
                isAdmin = m[2].equals("1");
                whoLabel.setText("Logged in as " + m[1] + (isAdmin ? "  (administrator)" : ""));
                adminPanel.setVisible(isAdmin);
                cards.show(root, "main");
                break;
            case common.MessageProtocol.ERROR:
                if (!loggedIn) {
                    JOptionPane.showMessageDialog(this, m[1], "Login failed", JOptionPane.WARNING_MESSAGE);
                    resetToLogin();
                } else {
                    statusLabel.setText(m[1]);
                    if (isAdmin) JOptionPane.showMessageDialog(this, m[1], "Server says", JOptionPane.WARNING_MESSAGE);
                    refreshControls();
                }
                break;
            case NO_POLL:
                options = new ArrayList<>();
                pollActive = false;
                questionLabel.setText("No poll yet. Waiting for the administrator...");
                rebuildOptions();
                results.setData(options, new int[0]);
                break;
            case POLL:
                options = new ArrayList<>();
                for (int i = 4; i < m.length; i++) options.add(m[i]);
                pollActive = m[3].equals("1");
                votedIndex = -1;
                questionLabel.setText("<html>" + escapeHtml(m[2]) + "</html>");
                statusLabel.setText(pollActive ? "Poll is open." : "Poll is closed.");
                rebuildOptions();
                break;
            case VOTED:
            case VOTE_ACCEPTED:
                votedIndex = Integer.parseInt(m[1]);
                statusLabel.setText("Your vote was recorded.");
                refreshControls();
                break;
            case RESULT_UPDATE: {
                int[] counts = new int[m.length - 3];
                for (int i = 3; i < m.length; i++) counts[i - 3] = Integer.parseInt(m[i]);
                if (counts.length == options.size()) results.setData(options, counts);
                break;
            }
            case POLL_CLOSED:
                pollActive = false;
                statusLabel.setText("The poll has been closed.");
                refreshControls();
                break;
            case USERS:
                usersModel.clear();
                for (int i = 1; i < m.length; i++) usersModel.addElement(m[i]);
                break;
            default:
                break;
        }
    }

    @Override
    public void onDisconnected(String reason) {
        SwingUtilities.invokeLater(() -> {
            if (loggedIn) JOptionPane.showMessageDialog(this, reason, "Disconnected", JOptionPane.WARNING_MESSAGE);
            resetToLogin();
        });
    }

    private void resetToLogin() {
        if (client != null) client.close();
        client = null;
        loggedIn = false;
        connectBtn.setEnabled(true);
        usersModel.clear();
        cards.show(root, "login");
    }

    // ------------------------------------------------------------ helpers
    private void rebuildOptions() {
        optionsPanel.removeAll();
        radios.clear();
        group = new ButtonGroup();
        for (String o : options) {
            JRadioButton r = new JRadioButton(o);
            group.add(r);
            radios.add(r);
            optionsPanel.add(r);
        }
        refreshControls();
        optionsPanel.revalidate();
        optionsPanel.repaint();
    }

    private void refreshControls() {
        boolean canVote = pollActive && votedIndex < 0;
        for (int i = 0; i < radios.size(); i++) {
            radios.get(i).setEnabled(canVote);
            if (votedIndex == i) radios.get(i).setSelected(true);
        }
        submitBtn.setEnabled(canVote && !radios.isEmpty());
        startBtn.setEnabled(!pollActive);
        closeBtn.setEnabled(pollActive);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ClientGUI().setVisible(true));
    }

    // ------------------------------------------------------------ bar chart
    static class ResultsPanel extends JPanel {
        private static final Color[] COLORS = {
            new Color(0x378ADD), new Color(0x1D9E75), new Color(0xD85A30), new Color(0x7F77DD), new Color(0xBA7517),
            new Color(0xD4537E), new Color(0x639922), new Color(0x888780), new Color(0xE24B4A), new Color(0x0F6E56)
        };
        private List<String> options = new ArrayList<>();
        private int[] counts = new int[0];

        void setData(List<String> options, int[] counts) {
            this.options = new ArrayList<>(options);
            this.counts = counts;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (options.isEmpty() || counts.length != options.size()) {
                g.drawString("No results yet.", getInsets().left + 12, getInsets().top + 20);
                g.dispose();
                return;
            }
            int total = 0;
            for (int c : counts) total += c;
            Insets in = getInsets();                          // space used by the titled border
            int x = in.left + 12, barMax = getWidth() - in.left - in.right - 24, top = in.top + 30;
            int rowH = Math.min(70, (getHeight() - top - in.bottom - 10) / options.size());
            FontMetrics fm = g.getFontMetrics();
            g.drawString("Total votes: " + total, x, in.top + 16);
            for (int i = 0; i < options.size(); i++) {
                int y = top + i * rowH;
                int pct = total == 0 ? 0 : Math.round(100f * counts[i] / total);
                String stats = counts[i] + (counts[i] == 1 ? " vote" : " votes") + " (" + pct + "%)";
                g.setColor(getForeground());
                g.drawString(options.get(i), x, y + 14);
                g.drawString(stats, x + barMax - fm.stringWidth(stats), y + 14);
                g.setColor(new Color(128, 128, 128, 50));
                g.fillRoundRect(x, y + 20, barMax, 16, 8, 8);
                int w = total == 0 ? 0 : (int) ((long) barMax * counts[i] / total);
                g.setColor(COLORS[i % COLORS.length]);
                if (w > 0) g.fillRoundRect(x, y + 20, Math.max(w, 8), 16, 8, 8);
            }
            g.dispose();
        }
    }
}
