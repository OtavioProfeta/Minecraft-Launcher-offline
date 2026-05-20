import javax.swing.*;
import javax.swing.border.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.net.URL;
import java.awt.Desktop;

/**
 * Main UI class — purely presentation logic.
 * All launch logic lives in GameLauncher; config persistence in LaunchConfig;
 * version/library analysis in VersionAnalyzer.
 */
public class MinecraftLauncher extends JFrame {

    // ==========================================
    // RESOURCES
    // ==========================================
    private static final String BACKGROUND_RESOURCE = "resource/images/minecraft_caves_2560x1440.png";
    private static final String LOGO_RESOURCE       = "resource/images/Global-Header_MCCB-Logo.png";
    private static final String ICON_RESOURCE       = "resource/images/icon_64x64.png";
    private static final String FONT_RESOURCE       = "resource/fonts/Minecraft.otf";
    private static final String FONT_BOLD_RESOURCE       = "resource/fonts/Minecraft-Bold.otf";

    // ==========================================
    // FONTS Minecraft
    // ==========================================
    private static Font minecraftFont;
    private static Font minecraftBoldFont;

    private static Font getMinecraftFont(float size) {
        return loadFont(minecraftFont, FONT_RESOURCE, size);
    }

    private static Font getMinecraftBoldFont(float size) {
        return loadFont(minecraftBoldFont, FONT_BOLD_RESOURCE, size);
    }

    private static Font loadFont(Font cache, String resource, float size) {
        if (cache == null) {
            try {
                URL fontUrl = MinecraftLauncher.class.getResource(resource);
                cache = Font.createFont(Font.TRUETYPE_FONT, fontUrl.openStream());
                GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(cache);
            } catch (Exception e) {
                cache = new Font("Monospaced", Font.BOLD, 12);
            }
        }
        return cache.deriveFont(size);
    }

    // ==========================================
    // UI THEME
    // ==========================================
    private static final Color  PANEL_BG     = new Color(48, 48, 48);
    private static final Color  TEXT_COLOR   = Color.WHITE;
    private static final Color  BUTTON_BG    = new Color(86, 86, 86);
    private static final Color  LAUNCH_GREEN = new Color(46, 139, 87);
    private static final Color  BORDER_COLOR = new Color(192, 192, 192);
    private static final Border FIELD_BORDER = BorderFactory.createCompoundBorder(
            new LineBorder(BORDER_COLOR, 1),
            new EmptyBorder(0, 5, 0, 0)
    );

    // ==========================================
    // FORM WIDGETS
    // ==========================================
    private JTextField    txtUser, txtGameDir, txtRam, txtJavaExe;
    private JComboBox<String> cboVersion;

    private LaunchConfig config;

    // ==========================================
    // CONSTRUCTOR
    // ==========================================
    public MinecraftLauncher() {
        config = LaunchConfig.load();
        setupWindow();
        buildUI();
        populateFields();
        refreshVersionList();
    }

    // ==========================================
    // WINDOW SETUP
    // ==========================================
    private void setupWindow() {
        setTitle("Minecraft Launcher");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(960, 560);
        setLocationRelativeTo(null);

        URL iconUrl = getClass().getResource(ICON_RESOURCE);
        if (iconUrl != null) setIconImage(new ImageIcon(iconUrl).getImage());
    }

    private void buildUI() {
        JPanel background = createBackgroundPanel();

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setOpaque(false);
        content.add(createFormPanel(),   BorderLayout.CENTER);
        content.add(createLaunchButton(), BorderLayout.SOUTH);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.insets = new Insets(30, 0, 0, 0);
        background.add(content, gbc);
        setContentPane(background);
    }

    // ==========================================
    // FORM PANEL
    // ==========================================
    private JPanel createFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(PANEL_BG);
        panel.setBorder(new EmptyBorder(15, 15, 15, 15));
        panel.setPreferredSize(new Dimension(500, 350));

        GridBagConstraints c = new GridBagConstraints();
        c.fill   = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(4, 4, 4, 4);

        // Logo row
        c.insets = new Insets(0, 0, 26, 0);
        addToGrid(panel, createLogoLabel(), c, 0, 0, 3, 1.0);
        c.insets = new Insets(4, 4, 4, 4);

        // Input fields
        txtUser    = createField();
        txtGameDir = createField();
        txtRam     = createField();
        txtJavaExe = createField();
        cboVersion = createVersionCombo();

        // Refresh version list when game dir loses focus (manual edit)
        txtGameDir.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { refreshVersionList(); }
        });

        JButton btnDir      = createSmallButton("...",   e -> browseDirectory(txtGameDir));
        JButton btnJava     = createSmallButton("...",   e -> browseJavaFile(txtJavaExe));
        JButton btnOpenDir  = createSmallButton("Abrir", e -> openInstallDir());

        // label | field | [button]
        addRow(panel, c, "Nome de Usuário:",      txtUser,    null,       2);
        addRow(panel, c, "Versão do Jogo:",       cboVersion, null,       3);
        addRow(panel, c, "Diretório do Jogo:",    txtGameDir, btnDir,     4);
        addRow(panel, c, "Memória RAM (Ex: 4G):", txtRam,     null,       5);
        addRow(panel, c, "Caminho do Java:",      txtJavaExe, btnJava,    6);
        addOpenDirRow(panel, c, "Diretório de Instalação:", btnOpenDir, 7);

        return panel;
    }

    /** Adds a label + field + optional browse button as a single horizontal row. */
    private void addRow(JPanel panel, GridBagConstraints c,
                        String label, Component field, JButton button, int row) {
        addToGrid(panel, createLabel(label), c, 0, row, 1, 0.3);
        if (button != null) {
            addToGrid(panel, field,  c, 1, row, 1, 0.6);
            addToGrid(panel, button, c, 2, row, 1, 0.1);
        } else {
            // No button → field spans the remaining two columns
            addToGrid(panel, field, c, 1, row, 2, 0.7);
        }
    }

    /** Adds a label + button row (no text field) spanning the remaining columns. */
    private void addOpenDirRow(JPanel panel, GridBagConstraints c,
                               String label, JButton button, int row) {
        addToGrid(panel, createLabel(label), c, 0, row, 1, 0.3);
        addToGrid(panel, button,             c, 1, row, 2, 0.7);
    }

    // ==========================================
    // VERSION LIST
    // ==========================================
    private void refreshVersionList() {
        if (cboVersion == null || txtGameDir == null) return;

        String selected = getSelectedVersion(); // preserve current selection
        cboVersion.removeAllItems();

        File versionsDir = new File(resolveEnvVars(txtGameDir.getText()), "versions");
        if (versionsDir.exists()) {
            File[] dirs = versionsDir.listFiles(File::isDirectory);
            if (dirs != null) {
                for (File dir : dirs) {
                    // A valid Minecraft version folder always contains a same-name .json file
                    if (new File(dir, dir.getName() + ".json").exists()) {
                        cboVersion.addItem(dir.getName());
                    }
                }
            }
        }

        cboVersion.setSelectedItem(selected);
    }

    private String getSelectedVersion() {
        Object sel = cboVersion != null ? cboVersion.getSelectedItem() : null;
        return sel != null ? sel.toString().trim() : "";
    }

    /** Resolves %APPDATA% in a path string (Windows). */
    private String resolveEnvVars(String path) {
        if (path.toUpperCase().contains("%APPDATA%")) {
            path = path.replace("%APPDATA%", System.getenv("APPDATA"));
        }
        return path;
    }

    // ==========================================
    // LAUNCH
    // ==========================================
    private void launchGame() {
        syncConfigFromUI();
        config.save();

        GameLauncher launcher = new GameLauncher(config);
        String result = launcher.launch();

        if (result == null) {
            System.exit(0);                 // game started successfully
        } else if (result.length() < 120) {
            showError(result, "Erro");
        } else {
            showLogDialog(result);          // long output → scrollable dialog
        }
    }

    /** Copies the current field values back into the config object. */
    private void syncConfigFromUI() {
        config.setUsername(txtUser.getText().trim());
        config.setVersion(getSelectedVersion());
        config.setGameDir(txtGameDir.getText().trim());
        config.setRam(txtRam.getText().trim());
        config.setJavaExe(txtJavaExe.getText().trim());
    }

    /** Populates the form fields from the loaded config. */
    private void populateFields() {
        txtUser.setText(config.getUsername());
        txtGameDir.setText(config.getGameDir());
        txtRam.setText(config.getRam());
        txtJavaExe.setText(config.getJavaExe());
        cboVersion.setSelectedItem(config.getVersion());
    }

    // ==========================================
    // UI COMPONENT FACTORIES
    // ==========================================
    private Image backgroundImage;

    private JPanel createBackgroundPanel() {
        JPanel panel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (backgroundImage == null) {
                    URL url = getClass().getResource(BACKGROUND_RESOURCE);
                    if (url != null) backgroundImage = new ImageIcon(url).getImage();
                }
                Graphics2D g2 = (Graphics2D) g;
                if (backgroundImage != null) {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g2.drawImage(backgroundImage, 0, 0, getWidth(), getHeight(), this);
                } else {
                    g2.setColor(new Color(20, 20, 20));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                }
            }
        };
        panel.setLayout(new GridBagLayout());
        return panel;
    }

    private JLabel createLogoLabel() {
        JLabel lbl = new JLabel("", SwingConstants.CENTER);
        URL logoUrl = getClass().getResource(LOGO_RESOURCE);
        if (logoUrl != null) {
            Image scaled = new ImageIcon(logoUrl).getImage()
                    .getScaledInstance(280, 50, Image.SCALE_SMOOTH);
            lbl.setIcon(new ImageIcon(scaled));
        } else {
            lbl.setText("MINECRAFT LAUNCHER");
            lbl.setForeground(TEXT_COLOR);
            lbl.setFont(getMinecraftFont(22f));
        }
        return lbl;
    }

    private JLabel createLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setForeground(TEXT_COLOR);
        lbl.setBorder(new EmptyBorder(0, 5, 0, 0));
        lbl.setFont(getMinecraftFont(13f));
        return lbl;
    }

    private JTextField createField() {
        JTextField field = new JTextField();
        field.setOpaque(false);
        field.setForeground(TEXT_COLOR);
        field.setBorder(FIELD_BORDER);
        field.setCaretColor(TEXT_COLOR);
        return field;
    }

    private JComboBox<String> createVersionCombo() {
        JComboBox<String> combo = new JComboBox<>();
        combo.setEditable(true);
        combo.setBorder(FIELD_BORDER);
        combo.setBackground(PANEL_BG);
        combo.setForeground(TEXT_COLOR);

        // Style the internal text editor
        Component editor = combo.getEditor().getEditorComponent();
        if (editor instanceof JTextField) {
            JTextField tf = (JTextField) editor;
            tf.setOpaque(false);
            tf.setForeground(TEXT_COLOR);
            tf.setCaretColor(TEXT_COLOR);
            tf.setBorder(null);
        }

        // Style the dropdown arrow button
        for (Component child : combo.getComponents()) {
            if (child instanceof AbstractButton) {
                JButton btn = (JButton) child;
                btn.setBackground(BUTTON_BG);
                btn.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, BORDER_COLOR));
                btn.setFocusPainted(false);
                btn.setPreferredSize(new Dimension(50, btn.getPreferredSize().height));
            }
        }
        return combo;
    }

    private JButton createSmallButton(String text, java.awt.event.ActionListener action) {
        JButton btn = new JButton(text);
        btn.setFont(getMinecraftFont(12f));
        btn.setBackground(BUTTON_BG);
        btn.setForeground(TEXT_COLOR);
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1));
        btn.setPreferredSize(new Dimension(30, 22));
        btn.addActionListener(action);
        return btn;
    }

    private JButton createLaunchButton() {
        JButton btn = new JButton("JOGAR");
        btn.setFont(getMinecraftBoldFont(18f));
        btn.setBackground(LAUNCH_GREEN);
        btn.setForeground(TEXT_COLOR);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setPreferredSize(new Dimension(500, 45));
        btn.addActionListener(e -> launchGame());
        return btn;
    }

    private void addToGrid(JPanel panel, Component comp, GridBagConstraints c,
                           int x, int y, int width, double weightx) {
        c.gridx = x; c.gridy = y; c.gridwidth = width; c.weightx = weightx;
        panel.add(comp, c);
    }

    // ==========================================
    // FILE CHOOSERS
    // ==========================================
    private void openInstallDir() {
        String path = resolveEnvVars(txtGameDir.getText().trim());
        File dir = new File(path);
        if (!dir.isDirectory()) {
            showError("Diretório não encontrado:\n" + path, "Erro");
            return;
        }
        try {
            Desktop.getDesktop().open(dir);
        } catch (Exception e) {
            showError("Não foi possível abrir o diretório:\n" + e.getMessage(), "Erro");
        }
    }

    private void browseDirectory(JTextField target) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Selecione a pasta do jogo (.minecraft)");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        applyInitialDir(chooser, target.getText());
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
            refreshVersionList(); // auto-refresh after picking a new folder
        }
    }

    private void browseJavaFile(JTextField target) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Selecione o executável do Java (javaw.exe)");
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setFileFilter(new FileNameExtensionFilter("Executáveis (*.exe)", "exe"));
        applyInitialDir(chooser, target.getText());
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            target.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void applyInitialDir(JFileChooser chooser, String path) {
        if (path == null || path.isEmpty()) return;
        File f = new File(resolveEnvVars(path));
        chooser.setCurrentDirectory(f.isDirectory() ? f : f.getParentFile());
    }

    // ==========================================
    // DIALOGS
    // ==========================================
    private void showError(String message, String title) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE);
    }

    private void showLogDialog(String logText) {
        JTextArea area = new JTextArea(logText);
        area.setEditable(false);
        area.setForeground(new Color(255, 100, 100));
        area.setBackground(new Color(30, 30, 30));
        area.setFont(new Font("Consolas", Font.PLAIN, 12));
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(700, 400));
        JOptionPane.showMessageDialog(this, scroll,
                "Erro na Inicialização do Minecraft", JOptionPane.ERROR_MESSAGE);
    }

    // ==========================================
    // ENTRY POINT
    // ==========================================
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MinecraftLauncher().setVisible(true));
    }
}
