import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.*;

public class CafeApp {
    // --- PHẦN 1: ENUM & MODEL CHO PHÂN QUYỀN & OOP ---
    public enum Role {
        STAFF("Nhân viên"),
        MANAGER("Quản lý");

        final String label;
        Role(String label) { this.label = label; }
    }

    public record User(String username, String password, String fullName, Role role) {}

    public static abstract class Product {
        protected String name;
        protected long basePrice;
        protected Map<String, Double> recipe;

        public Product(String name, long basePrice, Map<String, Double> recipe) {
            this.name = name;
            this.basePrice = basePrice;
            this.recipe = recipe;
        }

        public String name() { return name; }
        public long basePrice() { return basePrice; }
        public Map<String, Double> recipe() { return recipe; }
        public abstract boolean isFood();
    }

    public static class Drink extends Product {
        public Drink(String name, long basePrice, Map<String, Double> recipe) {
            super(name, basePrice, recipe);
        }
        @Override
        public boolean isFood() { return false; }
    }

    public static class Food extends Product {
        public Food(String name, long basePrice, Map<String, Double> recipe) {
            super(name, basePrice, recipe);
        }
        @Override
        public boolean isFood() { return true; }
    }

    static final List<User> USERS = List.of(
            new User("admin", "123", "Lê Văn A (Admin)", Role.MANAGER),
            new User("nv1", "123", "Nguyễn Văn B (Nhân viên)", Role.STAFF)
    );

    User currentUser = null;

    NavButton barNavBtn;
    CardLayout cardLayout = new CardLayout();
    JPanel cards = new JPanel(cardLayout);
    String selectedTable;
    JFrame frame;

    static final double WARN_PCT = 0.5;

    // MÀU SẮC GIAO DIỆN
    static final Color BG = new Color(0xFFF8EE), PANEL = new Color(0xFFFFFF),
            CARD = new Color(0xFFEFD9), LINE = new Color(0xF0D9BC),
            GOLD = new Color(0xFF8A3D), TEXT = new Color(0x4A2C1A),
            MUTED = new Color(0x9A7B66), GREEN = new Color(0x2FB36B),
            RED = new Color(0xE5484D), AMBER = new Color(0xFFB92E),
            GRAY = new Color(0xC9B8A8), SEL = new Color(0xFFD8B0),
            FLOOR = new Color(0xFFF1DE);

    enum Size {
        M(1.0, 0), L(1.25, 7000);
        final double factor;
        final long extra;
        Size(double f, long e) { factor = f; extra = e; }
    }

    enum TableStatus {
        FREE("Trống", GREEN),
        SERVING("Đang phục vụ", GOLD),
        DIRTY("Cần dọn", RED),
        RESERVED("Đã đặt trước", AMBER);
        final String label;
        final Color color;
        TableStatus(String label, Color color) {
            this.label = label;
            this.color = color;
        }
    }

    static class TableInfo {
        TableStatus status = TableStatus.FREE;
        int capacity = 4;
        int guests = 0;
        TableInfo(int capacity) { this.capacity = capacity; }
    }

    record Topping(String name, long price, String ingredient, double amount) {}

    record Line(Product drink, Size size, List<Topping> toppings, String sugar, String ice, int qty) {
        long price() {
            return (drink.basePrice() + size.extra + toppings.stream().mapToLong(Topping::price).sum()) * qty;
        }
        String describe() {
            if (drink.isFood()) return qty + "x " + drink.name();
            String t = toppings.isEmpty() ? "" : " + " + String.join(", ", toppings.stream().map(Topping::name).toList());
            return qty + "x " + drink.name() + " (" + size + ", đường " + sugar + ", đá " + ice + ")" + t;
        }
    }

    static class Order {
        static int seq = 0;
        final int ticket;
        final String table, method;
        final List<Line> lines;
        int hour = LocalTime.now().getHour(); // Lưu lại giờ tạo đơn

        Order(String table, String method, List<Line> lines) {
            this.ticket = ++seq;
            this.table = table;
            this.method = method;
            this.lines = lines;
        }

        Order(int ticket, String table, String method, List<Line> lines, int hour) {
            this.ticket = ticket;
            this.table = table;
            this.method = method;
            this.lines = lines;
            this.hour = hour;
            if (ticket > seq) seq = ticket;
        }

        long total() { return lines.stream().mapToLong(Line::price).sum(); }
    }

    static class Inventory {
        final Map<String, double[]> stock = new TreeMap<>();
        void add(String n, double q, double min) { stock.put(n, new double[]{q, min, q}); }
        double pct(String n) {
            double[] s = stock.get(n);
            return s[2] <= 0 ? 0 : s[0] / s[2];
        }
        int level(String n) {
            double[] s = stock.get(n);
            if (s[0] <= s[1]) return 0;
            return pct(n) < WARN_PCT ? 1 : 2;
        }
        Map<String, Double> need(Line l) {
            Map<String, Double> r = new HashMap<>();
            l.drink().recipe().forEach((k, v) -> r.merge(k, v * l.size().factor * l.qty(), Double::sum));
            l.toppings().forEach(t -> r.merge(t.ingredient(), t.amount() * l.qty(), Double::sum));
            if (!l.drink().isFood()) r.merge("Ly + nắp", (double) l.qty(), Double::sum);
            return r;
        }
        boolean enough(Line l) {
            return need(l).entrySet().stream().allMatch(e ->
                    stock.containsKey(e.getKey()) && stock.get(e.getKey())[0] >= e.getValue());
        }
        void deduct(Line l) { need(l).forEach((k, v) -> stock.get(k)[0] -= v); }
    }

    record Result(Order order, List<String> rejected) {}

    static class Service {
        final Inventory inv = new Inventory();
        final List<Order> bar = new ArrayList<>(), paid = new ArrayList<>();
        final Map<String, Integer> sold = new TreeMap<>();
        final Map<Integer, Long> byHour = new TreeMap<>();
        final Map<Integer, Integer> guestsByHour = new TreeMap<>();
        final Map<String, TableInfo> tables = new LinkedHashMap<>();
        final Map<String, List<Order>> tableOrders = new HashMap<>();

        Service() {
            for (int i = 1; i <= 8; i++) {
                int cap = (i <= 4) ? 4 : (i <= 6 ? 2 : 6);
                tables.put("Bàn " + i, new TableInfo(cap));
            }
        }

        Result checkout(String table, List<Line> lines, String method) {
            List<Line> ok = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            for (Line l : lines) {
                if (inv.enough(l)) {
                    inv.deduct(l);
                    ok.add(l);
                } else {
                    rejected.add(l.describe());
                }
            }
            if (ok.isEmpty()) return new Result(null, rejected);
            Order o = new Order(table, method, ok);
            addPaidOrder(o);
            bar.add(o);
            if (tables.containsKey(table)) {
                TableInfo info = tables.get(table);
                info.status = TableStatus.SERVING;
                if (info.guests == 0) info.guests = 1;
                tableOrders.computeIfAbsent(table, k -> new ArrayList<>()).add(o);
            }
            return new Result(o, rejected);
        }

        void addPaidOrder(Order o) {
            paid.add(o);
            byHour.merge(o.hour, o.total(), Long::sum);
            guestsByHour.merge(o.hour, 1, Integer::sum);
            o.lines.forEach(l -> sold.merge(l.drink().name(), l.qty(), Integer::sum));
        }
    }

    // --- COMPONENT VẼ BIỂU ĐỒ DOANH THU & SỐ KHÁCH THEO GIỜ ---
    static class ChartPanel extends JPanel {
        private Map<Integer, Long> byHour = new TreeMap<>();
        private Map<Integer, Integer> guestsByHour = new TreeMap<>();
        public ChartPanel() {
            setOpaque(false);
            setPreferredSize(new Dimension(0, 220));
        }
        public void setData(Map<Integer, Long> byHour, Map<Integer, Integer> guestsByHour) {
            this.byHour = byHour;
            this.guestsByHour = guestsByHour;
            repaint();
        }
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int paddingLeft = 50, paddingBottom = 30, paddingTop = 25, paddingRight = 20;
            int chartW = w - paddingLeft - paddingRight;
            int chartH = h - paddingTop - paddingBottom;
            g2.setColor(LINE);
            g2.drawLine(paddingLeft, h - paddingBottom, w - paddingRight, h - paddingBottom);
            long maxRev = byHour.values().stream().max(Long::compare).orElse(100000L);
            if (maxRev == 0) maxRev = 100000L;
            int startHour = 6, endHour = 23;
            int totalHours = endHour - startHour + 1;
            int barWidth = Math.max(12, (chartW / totalHours) - 8);
            g2.setFont(font(Font.PLAIN, 11));
            for (int i = 0; i < totalHours; i++) {
                int hour = startHour + i;
                long rev = byHour.getOrDefault(hour, 0L);
                int guests = guestsByHour.getOrDefault(hour, 0);
                int x = paddingLeft + i * (chartW / totalHours) + 4;
                int barH = (int) ((double) rev / maxRev * (chartH - 25));
                if (rev > 0) {
                    g2.setColor(GOLD);
                    g2.fillRoundRect(x, h - paddingBottom - barH, barWidth, barH, 6, 6);
                    g2.setColor(TEXT);
                    g2.drawString((rev / 1000) + "k", x - 2, h - paddingBottom - barH - 4);
                } else {
                    g2.setColor(new Color(0x3A2D25));
                    g2.fillRoundRect(x, h - paddingBottom - 4, barWidth, 4, 2, 2);
                }
                g2.setColor(MUTED);
                g2.drawString(String.format("%02d:00", hour), x - 4, h - paddingBottom + 18);
                if (guests > 0) {
                    g2.setColor(GREEN);
                    g2.drawString("👤" + guests, x, h - paddingBottom - barH - 18);
                }
            }
            g2.setColor(GOLD);
            g2.fillRect(w - 180, 5, 12, 12);
            g2.setColor(TEXT);
            g2.drawString("Doanh thu", w - 162, 15);
            g2.setColor(GREEN);
            g2.drawString("👤 Số đơn", w - 85, 15);
            g2.dispose();
        }
    }

    // --- TIỆN ÍCH GIAO DIỆN ---
    static String fmt(long v) { return String.format("%,d", v).replace(',', '.') + "đ"; }
    static Font font(int style, int size) { return new Font("Segoe UI", style, size); }
    static Color lighten(Color c) { return new Color(Math.min(255, c.getRed() + 22), Math.min(255, c.getGreen() + 22), Math.min(255, c.getBlue() + 22)); }

    static class RoundButton extends JButton {
        final Color base;
        RoundButton(String text, Color base, Color fg) {
            super(text);
            this.base = base;
            setForeground(fg);
            setFont(font(Font.BOLD, 14));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = !isEnabled() ? GRAY : (getModel().isPressed() ? base.darker() : (getModel().isRollover() ? lighten(base) : base));
            g2.setColor(c);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static class Card extends JPanel {
        final Color fill;
        Card(LayoutManager lm, Color fill) {
            super(lm);
            this.fill = fill;
            setOpaque(false);
            setBorder(new EmptyBorder(14, 14, 14, 14));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), 22, 22);
            g2.setColor(LINE);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 22, 22);
            g2.dispose();
        }
    }

    static class NavButton extends JToggleButton {
        NavButton(String t) {
            super(t);
            setForeground(MUTED);
            setFont(font(Font.BOLD, 15));
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setBorder(new EmptyBorder(14, 18, 16, 18));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addChangeListener(e -> setForeground(isSelected() ? GOLD : MUTED));
        }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (isSelected()) {
                g.setColor(GOLD);
                g.fillRect(16, getHeight() - 3, getWidth() - 32, 3);
            }
        }
    }

    static void setupTheme() {
        try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) {}
        for (Object key : Collections.list(UIManager.getDefaults().keys())) {
            if (key instanceof String k && UIManager.get(k) instanceof Font f) {
                UIManager.put(k, new javax.swing.plaf.FontUIResource("Segoe UI", f.getStyle(), 14));
            }
        }
        Object[][] p = {
                {"Panel.background", BG}, {"OptionPane.background", BG},
                {"OptionPane.messageForeground", TEXT}, {"Label.foreground", TEXT},
                {"CheckBox.background", BG}, {"CheckBox.foreground", TEXT},
                {"RadioButton.background", BG}, {"RadioButton.foreground", TEXT},
                {"ComboBox.background", CARD}, {"ComboBox.foreground", TEXT},
                {"ComboBox.selectionBackground", GOLD}, {"ComboBox.selectionForeground", BG},
                {"ComboBox.buttonBackground", CARD}, {"Spinner.background", CARD},
                {"Spinner.foreground", TEXT}, {"FormattedTextField.background", CARD},
                {"FormattedTextField.foreground", TEXT}, {"TextField.background", CARD},
                {"TextField.foreground", TEXT}, {"TextField.caretForeground", TEXT},
                {"List.background", PANEL}, {"List.foreground", TEXT},
                {"List.selectionBackground", SEL}, {"List.selectionForeground", TEXT},
                {"Table.background", PANEL}, {"Table.foreground", TEXT},
                {"Table.gridColor", LINE}, {"Table.selectionBackground", SEL},
                {"Table.selectionForeground", TEXT}, {"TableHeader.background", CARD},
                {"TableHeader.foreground", GOLD}, {"TextArea.background", PANEL},
                {"TextArea.foreground", TEXT}, {"ScrollPane.background", BG},
                {"Viewport.background", PANEL}, {"ScrollBar.background", BG},
                {"ScrollBar.thumb", LINE}, {"ScrollBar.track", BG},
                {"ScrollBar.thumbShadow", LINE}, {"ScrollBar.thumbHighlight", LINE},
                {"ScrollBar.thumbDarkShadow", LINE}, {"Button.background", CARD},
                {"Button.foreground", TEXT}, {"Button.select", LINE}, {"Button.gradient", null}
        };
        for (Object[] kv : p) UIManager.put(kv[0], kv[1]);
    }

    static JLabel label(String t, int style, int size, Color c) {
        JLabel l = new JLabel(t);
        l.setFont(font(style, size));
        l.setForeground(c);
        return l;
    }
    static JScrollPane scroll(Component c) {
        JScrollPane s = new JScrollPane(c);
        s.setBorder(new LineBorder(LINE));
        s.getViewport().setBackground(PANEL);
        return s;
    }
    static JPanel page(LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setBackground(BG);
        p.setBorder(new EmptyBorder(18, 22, 18, 22));
        return p;
    }
    static JPanel clear(JPanel p) {
        p.setOpaque(false);
        return p;
    }
    static RoundButton btn(String t, Color bg, Color fg, Runnable r) {
        RoundButton b = new RoundButton(t, bg, fg);
        b.setPreferredSize(new Dimension(100, 42));
        b.addActionListener(e -> r.run());
        return b;
    }

    // --- DỮ LIỆU CỬA HÀNG ---
    final Service svc = new Service();
    final List<Product> menu = new ArrayList<>();
    final List<Product> foods = new ArrayList<>();
    final Map<String, Product> productByName = new HashMap<>();
    final Set<String> foodNames = new HashSet<>();
    final List<Topping> toppings = new ArrayList<>();
    final List<Line> cart = new ArrayList<>();
    final DefaultListModel<String> cartModel = new DefaultListModel<>(),
            barModel = new DefaultListModel<>(), detailModel = new DefaultListModel<>();
    final JPanel stockGrid = clear(new JPanel(new GridLayout(0, 3, 12, 12)));
    final JLabel stockSummary = label(" ", Font.BOLD, 14, TEXT);
    final ChartPanel chartPanel = new ChartPanel();
    final JTextArea reportArea = new JTextArea();
    final JLabel totalLabel = label("Tổng: 0đ", Font.BOLD, 20, GOLD);
    final JLabel kpiRevenue = label("0đ", Font.BOLD, 26, GOLD),
            kpiOrders = label("0", Font.BOLD, 26, GOLD),
            kpiAvg = label("0đ", Font.BOLD, 26, GOLD);
    final JComboBox<String> tableBox = new JComboBox<>(new String[]{
            "Bàn 1", "Bàn 2", "Bàn 3", "Bàn 4", "Bàn 5", "Bàn 6", "Bàn 7", "Bàn 8", "Mang đi"
    });
    final JComboBox<String> payBox = new JComboBox<>(new String[]{"MoMo", "VietQR", "Thẻ"});
    final JLabel detailTitle = label("Chọn một bàn", Font.BOLD, 20, TEXT);
    final JLabel detailStatus = label("", Font.BOLD, 14, MUTED);
    final JPanel detailButtons = clear(new JPanel(new GridLayout(0, 1, 8, 8)));
    final List<JComponent> tiles = new ArrayList<>();
    final Map<String, NavButton> nav = new LinkedHashMap<>();

    void addDrink(String name, long price, Map<String, Double> recipe) {
        Drink d = new Drink(name, price, recipe);
        menu.add(d);
        productByName.put(name, d);
    }

    void addFood(String name, long price, Map<String, Double> recipe) {
        Food f = new Food(name, price, recipe);
        foods.add(f);
        foodNames.add(name);
        productByName.put(name, f);
    }

    void ing(String nameWithUnit, double qty, double min) { svc.inv.add(nameWithUnit, qty, min); }

    CafeApp() {
        addDrink("Cà phê đen", 25_000, Map.of("Cà phê (g)", 20.0));
        addDrink("Cà phê sữa", 29_000, Map.of("Cà phê (g)", 20.0, "Sữa đặc (ml)", 30.0));
        addDrink("Bạc xỉu", 32_000, Map.of("Cà phê (g)", 12.0, "Sữa đặc (ml)", 30.0, "Sữa tươi (ml)", 100.0));
        addDrink("Latte", 45_000, Map.of("Cà phê (g)", 18.0, "Sữa tươi (ml)", 180.0));
        addDrink("Trà đào", 39_000, Map.of("Trà (g)", 5.0, "Syrup đào (ml)", 30.0));
        addDrink("Trà sữa", 42_000, Map.of("Trà (g)", 6.0, "Sữa tươi (ml)", 120.0));

        toppings.add(new Topping("Trân châu", 8_000, "Trân châu (g)", 50));
        toppings.add(new Topping("Thạch trái cây", 6_000, "Thạch (g)", 40));
        toppings.add(new Topping("Kem cheese", 10_000, "Kem cheese (g)", 40));

        svc.inv.add("Cà phê (g)", 600, 150);
        svc.inv.add("Sữa đặc (ml)", 1000, 200);
        svc.inv.add("Sữa tươi (ml)", 1500, 400);
        svc.inv.add("Trà (g)", 300, 60);
        svc.inv.add("Syrup đào (ml)", 600, 100);
        svc.inv.add("Trân châu (g)", 500, 100);
        svc.inv.add("Thạch (g)", 400, 80);
        svc.inv.add("Kem cheese (g)", 300, 80);
        svc.inv.add("Ly + nắp", 60, 15);
        ing("Bánh mì (ổ)", 40, 10);          ing("Bánh sandwich (lát)", 60, 12);   ing("Trứng gà (quả)", 120, 30);
        ing("Thịt nguội (g)", 1500, 300);    ing("Ức gà (g)", 5000, 1000);          ing("Thịt bò bằm (g)", 2500, 500);
        ing("Xúc xích (cây)", 80, 20);       ing("Bơ (g)", 1500, 300);              ing("Phô mai kem (g)", 1500, 300);
        ing("Phô mai mozzarella (g)", 1500, 300); ing("Kem tươi (ml)", 2000, 400);  ing("Rau xà lách (g)", 1200, 250);
        ing("Cà chua (g)", 2500, 500);       ing("Bột mì (g)", 5000, 1000);         ing("Bột chiên xù (g)", 2000, 400);
        ing("Đường (g)", 2500, 500);         ing("Sôcôla (g)", 800, 150);           ing("Mì Ý (g)", 3000, 600);
        ing("Gạo (g)", 6000, 1200);          ing("Khoai tây (g)", 6000, 1200);      ing("Dầu ăn (ml)", 4000, 800);
        ing("Sốt teriyaki (ml)", 1500, 300);

        Object[][] startLevel = {
                {"Syrup đào (ml)", 0.15}, {"Kem cheese (g)", 0.22}, {"Xúc xích (cây)", 0.15}, {"Sốt teriyaki (ml)", 0.15}, {"Phô mai mozzarella (g)", 0.18},
                {"Trà (g)", 0.40}, {"Sữa đặc (ml)", 0.42}, {"Bánh mì (ổ)", 0.35}, {"Thịt nguội (g)", 0.38}, {"Kem tươi (ml)", 0.45},
                {"Rau xà lách (g)", 0.30}, {"Trân châu (g)", 0.40},
                {"Cà phê (g)", 0.62}, {"Sữa tươi (ml)", 0.70}, {"Thạch (g)", 0.85}, {"Ly + nắp", 0.55}, {"Trứng gà (quả)", 0.90},
                {"Bơ (g)", 0.58}, {"Đường (g)", 0.75}, {"Bột mì (g)", 0.66}, {"Gạo (g)", 0.95}, {"Khoai tây (g)", 0.80},
                {"Dầu ăn (ml)", 0.60}, {"Ức gà (g)", 0.88}, {"Thịt bò bằm (g)", 0.72}, {"Phô mai kem (g)", 0.64}, {"Sôcôla (g)", 0.52},
                {"Mì Ý (g)", 0.92}, {"Bột chiên xù (g)", 0.78}, {"Cà chua (g)", 0.68},
        };
        for (Object[] r : startLevel) {
            double[] st = svc.inv.stock.get((String) r[0]);
            if (st != null) st[0] = Math.round(st[2] * (Double) r[1]);
        }
        addFood("Bánh mì ốp la", 25_000, Map.of("Bánh mì (ổ)", 1.0, "Trứng gà (quả)", 2.0, "Bơ (g)", 10.0, "Rau xà lách (g)", 20.0, "Cà chua (g)", 30.0));
        addFood("Bánh mì thịt nguội", 28_000, Map.of("Bánh mì (ổ)", 1.0, "Thịt nguội (g)", 60.0, "Bơ (g)", 10.0, "Rau xà lách (g)", 20.0, "Cà chua (g)", 30.0));
        addFood("Sandwich gà", 35_000, Map.of("Bánh sandwich (lát)", 2.0, "Ức gà (g)", 80.0, "Rau xà lách (g)", 20.0, "Cà chua (g)", 30.0, "Bơ (g)", 10.0));
        addFood("Croissant bơ", 30_000, Map.of("Bột mì (g)", 80.0, "Bơ (g)", 45.0, "Đường (g)", 10.0));
        addFood("Muffin chocolate", 28_000, Map.of("Bột mì (g)", 60.0, "Sôcôla (g)", 40.0, "Trứng gà (quả)", 1.0, "Đường (g)", 30.0, "Bơ (g)", 25.0));
        addFood("Bánh su kem", 22_000, Map.of("Bột mì (g)", 40.0, "Bơ (g)", 25.0, "Trứng gà (quả)", 1.0, "Kem tươi (ml)", 60.0, "Đường (g)", 15.0));
        addFood("Bánh flan", 20_000, Map.of("Trứng gà (quả)", 2.0, "Sữa tươi (ml)", 100.0, "Sữa đặc (ml)", 30.0, "Đường (g)", 20.0));
        addFood("Tiramisu", 42_000, Map.of("Phô mai kem (g)", 80.0, "Kem tươi (ml)", 50.0, "Cà phê (g)", 8.0, "Trứng gà (quả)", 1.0, "Đường (g)", 20.0, "Sôcôla (g)", 5.0));
        addFood("Cheesecake", 45_000, Map.of("Phô mai kem (g)", 120.0, "Bột mì (g)", 30.0, "Bơ (g)", 30.0, "Đường (g)", 30.0, "Trứng gà (quả)", 1.0));
        addFood("Mì Ý sốt bò bằm", 59_000, Map.of("Mì Ý (g)", 120.0, "Thịt bò bằm (g)", 100.0, "Cà chua (g)", 80.0, "Dầu ăn (ml)", 10.0));
        addFood("Cơm gà teriyaki", 55_000, Map.of("Gạo (g)", 150.0, "Ức gà (g)", 120.0, "Sốt teriyaki (ml)", 40.0, "Rau xà lách (g)", 20.0, "Dầu ăn (ml)", 10.0));
        addFood("Khoai tây chiên", 30_000, Map.of("Khoai tây (g)", 200.0, "Dầu ăn (ml)", 40.0));
        addFood("Gà viên chiên", 35_000, Map.of("Ức gà (g)", 120.0, "Bột chiên xù (g)", 30.0, "Trứng gà (quả)", 1.0, "Dầu ăn (ml)", 40.0));
        addFood("Phô mai que", 28_000, Map.of("Phô mai mozzarella (g)", 80.0, "Bột chiên xù (g)", 30.0, "Trứng gà (quả)", 1.0, "Dầu ăn (ml)", 30.0));
        addFood("Xúc xích nướng", 25_000, Map.of("Xúc xích (cây)", 2.0, "Dầu ăn (ml)", 5.0));

        // Khôi phục bộ nhớ dữ liệu cũ
        loadInventoryFromFile();
        loadOrdersFromFile();
        loadTablesFromFile();
    }

    // --- LƯU TRỮ DỮ LIỆU ĐỒNG BỘ TOÀN BỘ (PERSISTENCE) ---
    void saveReceiptToFile(Order o) {
        try (FileWriter writer = new FileWriter("receipts.txt", true)) {
            writer.write("========================================\n");
            writer.write("HOA DON #" + o.ticket + " - Ban: " + o.table + " - Gio: " + o.hour + ":00\n");
            writer.write("Hinh thuc thanh toan: " + o.method + "\n");
            writer.write("Chi tiet:\n");
            for (Line l : o.lines) {
                writer.write("  + " + l.describe() + " -> " + fmt(l.price()) + "\n");
            }
            writer.write("TONG TIEN: " + fmt(o.total()) + "\n");
            writer.write("========================================\n\n");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void saveOrdersToFile() {
        try (PrintWriter pw = new PrintWriter(new FileWriter("orders_data.txt"))) {
            for (Order o : svc.paid) {
                StringBuilder sb = new StringBuilder();
                sb.append(o.ticket).append(";").append(o.table).append(";").append(o.method).append(";").append(o.hour).append(";");
                List<String> lineStrs = new ArrayList<>();
                for (Line l : o.lines) {
                    String tops = String.join("|", l.toppings().stream().map(Topping::name).toList());
                    if (tops.isEmpty()) tops = "NONE";
                    lineStrs.add(l.drink().name() + "," + l.size() + "," + l.sugar() + "," + l.ice() + "," + l.qty() + "," + tops);
                }
                sb.append(String.join("#", lineStrs));
                pw.println(sb.toString());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void loadOrdersFromFile() {
        File file = new File("orders_data.txt");
        if (!file.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(";");
                if (parts.length >= 5) {
                    int ticket = Integer.parseInt(parts[0]);
                    String table = parts[1];
                    String method = parts[2];
                    int hour = Integer.parseInt(parts[3]);
                    String[] lineTokens = parts[4].split("#");

                    List<Line> lines = new ArrayList<>();
                    for (String token : lineTokens) {
                        String[] lp = token.split(",");
                        if (lp.length >= 6) {
                            String prodName = lp[0];
                            Product prod = productByName.get(prodName);
                            Size sz = Size.valueOf(lp[1]);
                            String sugar = lp[2];
                            String ice = lp[3];
                            int qty = Integer.parseInt(lp[4]);

                            List<Topping> chosenTops = new ArrayList<>();
                            if (!lp[5].equals("NONE")) {
                                String[] topNames = lp[5].split("\\|");
                                for (String tn : topNames) {
                                    toppings.stream().filter(t -> t.name().equals(tn)).findFirst().ifPresent(chosenTops::add);
                                }
                            }
                            if (prod != null) {
                                lines.add(new Line(prod, sz, chosenTops, sugar, ice, qty));
                            }
                        }
                    }
                    if (!lines.isEmpty()) {
                        Order o = new Order(ticket, table, method, lines, hour);
                        svc.addPaidOrder(o);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void saveTablesToFile() {
        try (PrintWriter pw = new PrintWriter(new FileWriter("tables_data.txt"))) {
            for (Map.Entry<String, TableInfo> entry : svc.tables.entrySet()) {
                String name = entry.getKey();
                TableInfo info = entry.getValue();
                pw.println(name + ";" + info.status.name() + ";" + info.capacity + ";" + info.guests);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void loadTablesFromFile() {
        File file = new File("tables_data.txt");
        if (!file.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(";");
                if (parts.length == 4) {
                    String name = parts[0];
                    TableStatus status = TableStatus.valueOf(parts[1]);
                    int capacity = Integer.parseInt(parts[2]);
                    int guests = Integer.parseInt(parts[3]);

                    TableInfo info = svc.tables.get(name);
                    if (info != null) {
                        info.status = status;
                        info.capacity = capacity;
                        info.guests = guests;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void saveInventoryToFile() {
        try (PrintWriter pw = new PrintWriter(new FileWriter("inventory.csv"))) {
            for (Map.Entry<String, double[]> entry : svc.inv.stock.entrySet()) {
                String name = entry.getKey();
                double currentQty = entry.getValue()[0];
                double minQty = entry.getValue()[1];
                double maxQty = entry.getValue()[2];
                pw.println(name + "," + currentQty + "," + minQty + "," + maxQty);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void loadInventoryFromFile() {
        File file = new File("inventory.csv");
        if (!file.exists()) return;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.split(",");
                if (parts.length == 4) {
                    String name = parts[0];
                    double currentQty = Double.parseDouble(parts[1]);
                    double minQty = Double.parseDouble(parts[2]);
                    double maxQty = Double.parseDouble(parts[3]);
                    svc.inv.stock.put(name, new double[]{currentQty, minQty, maxQty});
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    void saveAllData() {
        saveInventoryToFile();
        saveOrdersToFile();
        saveTablesToFile();
    }

    // --- PHẦN 2: DIALOG ĐĂNG NHẬP (AUTHENTICATION) ---
    boolean showLoginDialog() {
        JDialog dialog = new JDialog((Frame) null, "Đăng nhập hệ thống", true);
        dialog.setSize(400, 360);
        dialog.setLocationRelativeTo(null);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel p = new JPanel(new BorderLayout(15, 15));
        p.setBackground(BG);
        p.setBorder(new EmptyBorder(20, 25, 20, 25));

        JLabel title = label(" ĐĂNG NHẬP HỆ THỐNG", Font.BOLD, 18, GOLD);
        title.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel form = clear(new JPanel(new GridLayout(0, 1, 8, 8)));
        JTextField tfUser = new JTextField();
        JPasswordField tfPass = new JPasswordField();

        JComboBox<String> cbQuickUser = new JComboBox<>(new String[]{
                "-- Chọn nhanh tài khoản --",
                "Quản lý (admin / 123)",
                "Nhân viên (nv1 / 123)"
        });

        cbQuickUser.addActionListener(e -> {
            int idx = cbQuickUser.getSelectedIndex();
            if (idx == 1) { tfUser.setText("admin"); tfPass.setText("123"); }
            else if (idx == 2) { tfUser.setText("nv1"); tfPass.setText("123"); }
        });

        form.add(new JLabel("Chọn tài khoản mẫu:"));
        form.add(cbQuickUser);
        form.add(new JLabel("Tên đăng nhập:"));
        form.add(tfUser);
        form.add(new JLabel("Mật khẩu:"));
        form.add(tfPass);

        final boolean[] success = {false};
        RoundButton btnLogin = new RoundButton("Đăng nhập", GOLD, BG);
        btnLogin.addActionListener(e -> {
            String u = tfUser.getText().trim();
            String pwd = new String(tfPass.getPassword());
            Optional<User> found = USERS.stream()
                    .filter(user -> user.username().equalsIgnoreCase(u) && user.password().equals(pwd))
                    .findFirst();
            if (found.isPresent()) {
                currentUser = found.get();
                success[0] = true;
                dialog.dispose();
            } else {
                JOptionPane.showMessageDialog(dialog, "Sai tên đăng nhập hoặc mật khẩu!", "Lỗi", JOptionPane.ERROR_MESSAGE);
            }
        });

        p.add(title, BorderLayout.NORTH);
        p.add(form, BorderLayout.CENTER);
        p.add(btnLogin, BorderLayout.SOUTH);

        dialog.setContentPane(p);
        dialog.setVisible(true);
        return success[0];
    }

    void show() {
        if (!showLoginDialog()) {
            System.exit(0);
            return;
        }

        frame = new JFrame("Ứng dụng quản lý nhà hàng - [" + currentUser.role().label + "]");
        frame.setContentPane(buildRoot());
        frame.setSize(1180, 760);
        frame.setLocationRelativeTo(null);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveAllData();
            }
        });

        refreshAll();
        frame.setVisible(true);
    }

    JPanel buildRoot() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(BG);
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(PANEL);
        header.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, LINE), new EmptyBorder(0, 22, 0, 22)));

        JLabel logo = label(" Quản Lý Nhà Hàng", Font.BOLD, 22, GOLD);
        header.add(logo, BorderLayout.WEST);

        JPanel navBar = clear(new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0)));
        ButtonGroup grp = new ButtonGroup();
        String[][] tabs = {{"tables", "Sơ đồ bàn"}, {"order", "Order"}, {"bar", "Bếp"}, {"stock", "Kho"}, {"report", "Báo cáo"}};

        for (String[] t : tabs) {
            NavButton b = new NavButton(t[1]);
            nav.put(t[0], b);
            grp.add(b);
            navBar.add(b);
            if (t[0].equals("bar")) barNavBtn = b;

            if (currentUser.role() == Role.STAFF && (t[0].equals("stock") || t[0].equals("report"))) {
                b.setEnabled(false);
                b.setToolTipText("Chỉ Quản lý mới có quyền truy cập tab này");
            } else {
                b.addActionListener(e -> showCard(t[0]));
            }
        }
        header.add(navBar, BorderLayout.CENTER);

        JPanel userPanel = clear(new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)));
        JLabel lblUser = label("👤 " + currentUser.fullName() + " (" + currentUser.role().label + ")", Font.BOLD, 13, TEXT);
        RoundButton btnLogout = new RoundButton("Đăng xuất", LINE, TEXT);
        btnLogout.setPreferredSize(new Dimension(95, 32));
        btnLogout.setFont(font(Font.BOLD, 12));
        btnLogout.addActionListener(e -> {
            saveAllData();
            frame.dispose();
            show();
        });

        userPanel.add(lblUser);
        userPanel.add(btnLogout);
        header.add(userPanel, BorderLayout.EAST);

        cards.setBackground(BG);
        cards.add(buildTables(), "tables");
        cards.add(buildOrder(), "order");
        cards.add(buildBar(), "bar");
        cards.add(buildStock(), "stock");
        cards.add(buildReport(), "report");

        root.add(header, BorderLayout.NORTH);
        root.add(cards, BorderLayout.CENTER);
        showCard("tables");
        return root;
    }

    void showCard(String key) {
        cardLayout.show(cards, key);
        nav.get(key).setSelected(true);
    }

    class Tile extends JComponent {
        final String name;
        Tile(String n) {
            name = n;
            setPreferredSize(new Dimension(145, 155));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { selectTable(name); }
            });
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            TableInfo info = svc.tables.get(name);
            if (info == null) { g2.dispose(); return; }
            TableStatus st = info.status;
            int s = 76, x = (getWidth() - s) / 2, y = Math.max(18, (getHeight() - (s + 56)) / 2 + 14);
            int cap = info.capacity;
            int activeGuests = info.guests;
            for (int i = 0; i < cap; i++) {
                int cx = x, cy = y, cw = 20, ch = 11;
                if (cap == 2) {
                    if (i == 0) { cx = x - 14; cy = y + s / 2 - 10; cw = 11; ch = 20; }
                    else { cx = x + s + 3; cy = y + s / 2 - 10; cw = 11; ch = 20; }
                } else {
                    if (i == 0) { cx = x + s / 2 - 10; cy = y - 14; cw = 20; ch = 11; }
                    else if (i == 1) { cx = x + s / 2 - 10; cy = y + s + 3; cw = 20; ch = 11; }
                    else if (i == 2) { cx = x - 14; cy = y + s / 2 - 10; cw = 11; ch = 20; }
                    else if (i == 3) { cx = x + s + 3; cy = y + s / 2 - 10; cw = 11; ch = 20; }
                    else {
                        double angle = (2.0 * Math.PI * i) / cap - Math.PI / 2;
                        cx = (int) (x + s / 2 + (s / 2 + 8) * Math.cos(angle) - 8);
                        cy = (int) (y + s / 2 + (s / 2 + 8) * Math.sin(angle) - 8);
                        cw = 14; ch = 14;
                    }
                }
                if (st == TableStatus.SERVING || st == TableStatus.RESERVED) {
                    if (i < activeGuests) {
                        g2.setColor(TEXT);
                        g2.fillOval(cx, cy, cw, ch);
                    }
                } else {
                    g2.setColor(LINE);
                    g2.fillOval(cx, cy, cw, ch);
                }
            }
            g2.setColor(st.color);
            g2.fillRoundRect(x, y, s, s, 26, 26);
            if (name.equals(selectedTable)) {
                g2.setColor(GOLD);
                g2.setStroke(new BasicStroke(3f));
                g2.drawRoundRect(x - 4, y - 4, s + 8, s + 8, 30, 30);
            }
            g2.setColor(st == TableStatus.FREE || st == TableStatus.RESERVED ? BG : TEXT);
            g2.setFont(font(Font.BOLD, 17));
            String displayName = name.replace("Bàn ", "B");
            g2.drawString(displayName, x + (s - g2.getFontMetrics().stringWidth(displayName)) / 2, y + s / 2 + 6);
            g2.setColor(MUTED);
            g2.setFont(font(Font.PLAIN, 12));
            String subText;
            if (st == TableStatus.SERVING || st == TableStatus.RESERVED) {
                subText = info.guests + "/" + info.capacity + " khách";
            } else {
                subText = st.label + " (" + info.capacity + " chỗ)";
            }
            g2.drawString(subText, (getWidth() - g2.getFontMetrics().stringWidth(subText)) / 2, y + s + 34);
            g2.dispose();
        }
    }

    JPanel buildTables() {
        JPanel pg = page(new BorderLayout(18, 0));
        Card floor = new Card(new BorderLayout(0, 10), FLOOR);
        floor.add(label("Sơ đồ bàn", Font.BOLD, 22, TEXT), BorderLayout.NORTH);
        JPanel grid = clear(new JPanel(new GridLayout(2, 4, 8, 8)));
        for (String n : svc.tables.keySet()) {
            Tile t = new Tile(n);
            tiles.add(t);
            grid.add(t);
        }
        floor.add(grid, BorderLayout.CENTER);
        JPanel legend = clear(new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 0)));
        for (TableStatus st : TableStatus.values()) {
            JLabel l = label(st.label, Font.PLAIN, 13, MUTED);
            l.setIcon(new Icon() {
                public void paintIcon(Component c, Graphics g, int x, int y) {
                    g.setColor(st.color);
                    g.fillRoundRect(x, y, 14, 14, 5, 5);
                }
                public int getIconWidth() { return 14; }
                public int getIconHeight() { return 14; }
            });
            l.setIconTextGap(6);
            legend.add(l);
        }
        floor.add(legend, BorderLayout.SOUTH);
        Card detail = new Card(new BorderLayout(0, 10), CARD);
        detail.setPreferredSize(new Dimension(330, 0));
        JPanel top = clear(new JPanel(new GridLayout(0, 1, 0, 2)));
        top.add(detailTitle);
        top.add(detailStatus);
        JList<String> dl = new JList<>(detailModel);
        dl.setOpaque(false);
        dl.setBackground(CARD);
        JScrollPane sp = scroll(dl);
        sp.getViewport().setBackground(CARD);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        detail.add(top, BorderLayout.NORTH);
        detail.add(sp, BorderLayout.CENTER);
        detail.add(detailButtons, BorderLayout.SOUTH);
        pg.add(floor, BorderLayout.CENTER);
        pg.add(detail, BorderLayout.EAST);
        return pg;
    }

    void selectTable(String name) {
        selectedTable = name;
        refreshAll();
    }

    void setStatus(String name, TableStatus st) {
        TableInfo info = svc.tables.get(name);
        if (info != null) {
            info.status = st;
            if (st == TableStatus.DIRTY || st == TableStatus.FREE) {
                info.guests = 0;
                svc.tableOrders.remove(name);
            }
        }
        saveTablesToFile();
        refreshAll();
    }

    void refreshDetail() {
        detailButtons.removeAll();
        detailModel.clear();
        if (selectedTable == null) {
            detailTitle.setText("Chọn một bàn");
            detailStatus.setText("");
        } else {
            TableInfo info = svc.tables.get(selectedTable);
            TableStatus st = info.status;
            detailTitle.setText(selectedTable + " (" + info.capacity + " chỗ)");
            detailStatus.setText(st == TableStatus.SERVING ? "Đang phục vụ: " + info.guests + " khách" : st.label);
            detailStatus.setForeground(st.color);
            for (Order o : svc.tableOrders.getOrDefault(selectedTable, List.of())) {
                for (Line l : o.lines) {
                    detailModel.addElement("<html><div style='width: 230px'><font color='#E0A94B'>#" + o.ticket + "</font> " + l.describe() + "</div></html>");
                }
            }
            String t = selectedTable;
            switch (st) {
                case FREE -> {
                    detailButtons.add(btn("Nhận bàn & Gọi món", GOLD, BG, () -> promptAndOrder(t)));
                    detailButtons.add(btn("Đánh dấu đã đặt trước", LINE, TEXT, () -> {
                        JSpinner sp = new JSpinner(new SpinnerNumberModel(2, 1, info.capacity, 1));
                        if (JOptionPane.showConfirmDialog(frame, sp, "Số khách đặt trước:", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                            info.status = TableStatus.RESERVED;
                            info.guests = (Integer) sp.getValue();
                            saveTablesToFile();
                            refreshAll();
                        }
                    }));
                }
                case RESERVED -> {
                    detailButtons.add(btn("Khách đến & Gọi món", GOLD, BG, () -> promptAndOrder(t)));
                    detailButtons.add(btn("Bỏ đặt trước", LINE, TEXT, () -> setStatus(t, TableStatus.FREE)));
                }
                case SERVING -> {
                    detailButtons.add(btn("Thêm món (Order)", GOLD, BG, () -> { tableBox.setSelectedItem(t); showCard("order"); }));
                    detailButtons.add(btn("Khách rời bàn", LINE, TEXT, () -> setStatus(t, TableStatus.DIRTY)));
                }
                case DIRTY -> {
                    detailButtons.add(btn("Đã dọn xong", GREEN, BG, () -> setStatus(t, TableStatus.FREE)));
                }
            }

            if (currentUser.role() == Role.MANAGER) {
                detailButtons.add(btn("Đổi sức chứa", LINE, MUTED, () -> {
                    String input = JOptionPane.showInputDialog(frame, "Nhập số chỗ ngồi mới cho " + t + ":", info.capacity);
                    if (input != null) {
                        try {
                            int newCap = Integer.parseInt(input.trim());
                            if (newCap > 0) info.capacity = newCap;
                            saveTablesToFile();
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(frame, "Giá trị không hợp lệ.");
                        }
                        refreshAll();
                    }
                }));
            }
        }
        detailButtons.revalidate();
        detailButtons.repaint();
    }

    void promptAndOrder(String tableName) {
        TableInfo info = svc.tables.get(tableName);
        JSpinner guestSpinner = new JSpinner(new SpinnerNumberModel(2, 1, info.capacity, 1));
        JPanel panel = new JPanel(new GridLayout(0, 1, 6, 6));
        panel.add(new JLabel("Nhập số lượng khách (Tối đa " + info.capacity + " chỗ):"));
        panel.add(guestSpinner);
        int result = JOptionPane.showConfirmDialog(frame, panel, "Xác định số lượng khách - " + tableName, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (result == JOptionPane.OK_OPTION) {
            info.status = TableStatus.SERVING;
            info.guests = (Integer) guestSpinner.getValue();
            saveTablesToFile();
            tableBox.setSelectedItem(tableName);
            showCard("order");
            refreshAll();
        }
    }

    JPanel buildOrder() {
        JPanel pg = page(new BorderLayout(18, 0));
        JPanel left = clear(new JPanel(new BorderLayout(0, 12)));
        CardLayout menuLayout = new CardLayout();
        JPanel menuCards = clear(new JPanel(menuLayout));
        menuCards.add(menuGrid(menu), "drinks");
        menuCards.add(menuGrid(foods), "foods");
        JPanel menuTabs = clear(new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0)));
        ButtonGroup mg = new ButtonGroup();
        String[][] mt = {{"drinks", "Đồ uống"}, {"foods", "Món ăn"}};
        for (String[] t : mt) {
            NavButton b = new NavButton(t[1]); mg.add(b); menuTabs.add(b);
            b.addActionListener(e -> menuLayout.show(menuCards, t[0]));
            if (t[0].equals("drinks")) b.setSelected(true);
        }
        left.add(menuTabs, BorderLayout.NORTH);
        left.add(menuCards, BorderLayout.CENTER);
        Card east = new Card(new BorderLayout(0, 10), CARD);
        east.setPreferredSize(new Dimension(370, 0));
        JList<String> cartList = new JList<>(cartModel);
        JScrollPane csp = scroll(cartList);
        csp.getViewport().setBackground(PANEL);
        csp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JPanel form = clear(new JPanel(new GridLayout(0, 2, 8, 8)));
        form.add(label("Bàn (hoặc QR)", Font.PLAIN, 14, MUTED));
        form.add(tableBox);
        form.add(label("Thanh toán", Font.PLAIN, 14, MUTED));
        form.add(payBox);
        JPanel south = clear(new JPanel(new GridLayout(0, 1, 8, 8)));
        south.add(totalLabel);
        south.add(form);
        south.add(btn("Xóa món đã chọn", LINE, TEXT, () -> {
            int i = cartList.getSelectedIndex();
            if (i >= 0) { cart.remove(i); renderCart(); }
        }));
        south.add(btn("Thanh toán", GOLD, BG, this::checkout));
        east.add(label("Giỏ hàng", Font.BOLD, 20, TEXT), BorderLayout.NORTH);
        east.add(csp, BorderLayout.CENTER);
        east.add(south, BorderLayout.SOUTH);
        pg.add(left, BorderLayout.CENTER);
        pg.add(east, BorderLayout.EAST);
        return pg;
    }

    JScrollPane menuGrid(List<Product> items) {
        JPanel grid = clear(new JPanel(new GridLayout(0, 3, 12, 12)));
        for (Product d : items) {
            RoundButton b = btn("<html><div style='text-align:center'><b>" + d.name() + "</b><br><font color='#E0A94B'>" + fmt(d.basePrice()) + "</font></div></html>",
                    CARD, TEXT, () -> { if (d.isFood()) addFoodToCart(d); else addToCart(d); });
            b.setPreferredSize(new Dimension(170, 96)); grid.add(b);
        }
        JPanel wrap = clear(new JPanel(new BorderLayout())); wrap.add(grid, BorderLayout.NORTH);
        JScrollPane sp = scroll(wrap); sp.getViewport().setOpaque(false); sp.setOpaque(false); sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    void addToCart(Product d) {
        JRadioButton m = new JRadioButton("M", true), l = new JRadioButton("L (+7.000đ)");
        ButtonGroup g = new ButtonGroup();
        g.add(m); g.add(l);
        JPanel sizeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        sizeRow.add(m); sizeRow.add(l);
        JComboBox<String> sugar = new JComboBox<>(new String[]{"0%", "30%", "50%", "70%", "100%"});
        sugar.setSelectedItem("70%");
        JComboBox<String> ice = new JComboBox<>(new String[]{"Không", "Ít", "Vừa", "Nhiều"});
        ice.setSelectedItem("Vừa");
        JSpinner qty = new JSpinner(new SpinnerNumberModel(1, 1, 10, 1));
        List<JCheckBox> tops = new ArrayList<>();
        JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
        p.add(new JLabel("Size:"));
        p.add(sizeRow);
        p.add(new JLabel("Topping:"));
        for (Topping t : toppings) {
            JCheckBox c = new JCheckBox(t.name() + " (+" + fmt(t.price()) + ")");
            tops.add(c);
            p.add(c);
        }
        p.add(new JLabel("Đường:")); p.add(sugar);
        p.add(new JLabel("Đá:")); p.add(ice);
        p.add(new JLabel("Số lượng:")); p.add(qty);
        if (JOptionPane.showConfirmDialog(frame, p, d.name(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        List<Topping> chosen = new ArrayList<>();
        for (int i = 0; i < tops.size(); i++) {
            if (tops.get(i).isSelected()) chosen.add(toppings.get(i));
        }
        cart.add(new Line(d, m.isSelected() ? Size.M : Size.L, chosen,
                (String) sugar.getSelectedItem(), (String) ice.getSelectedItem(), (Integer) qty.getValue()));
        renderCart();
    }

    int canMake(Product f, boolean minusCart) {
        Map<String, Double> used = new HashMap<>();
        if (minusCart) for (Line l : cart) svc.inv.need(l).forEach((k, v) -> used.merge(k, v, Double::sum));
        int max = Integer.MAX_VALUE;
        for (Map.Entry<String, Double> e : f.recipe().entrySet()) {
            double left = svc.inv.stock.get(e.getKey())[0] - used.getOrDefault(e.getKey(), 0.0);
            max = Math.min(max, (int) Math.floor(left / e.getValue() + 1e-9));
        }
        return Math.max(max, 0);
    }

    static String num(double v) { return v == Math.floor(v) ? String.format("%.0f", v) : String.format("%.1f", v); }

    void addFoodToCart(Product f) {
        if (canMake(f, false) <= 0) { JOptionPane.showMessageDialog(frame, f.name() + " đã hết nguyên liệu."); return; }
        int left = canMake(f, true);
        if (left <= 0) { JOptionPane.showMessageDialog(frame, "Nguyên liệu còn lại chỉ đủ cho các món bạn đã chọn trong giỏ hàng."); return; }
        JSpinner qty = new JSpinner(new SpinnerNumberModel(1, 1, Math.min(20, left), 1));
        JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
        p.add(new JLabel("Giá: " + fmt(f.basePrice()) + " / phần"));
        p.add(new JLabel("Số lượng:")); p.add(qty);
        if (JOptionPane.showConfirmDialog(frame, p, f.name(), JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        cart.add(new Line(f, Size.M, List.of(), "", "", (Integer) qty.getValue()));
        renderCart();
    }

    void renderCart() {
        cartModel.clear();
        long total = 0;
        for (Line l : cart) {
            cartModel.addElement("<html><div style='width:260px'>" + l.describe() + "<br><font color='#E0A94B'>" + fmt(l.price()) + "</font></div></html>");
            total += l.price();
        }
        totalLabel.setText("Tổng: " + fmt(total));
    }

    void checkout() {
        if (cart.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Giỏ hàng đang trống.");
            return;
        }
        long total = cart.stream().mapToLong(Line::price).sum();
        String method = (String) payBox.getSelectedItem();
        String table = (String) tableBox.getSelectedItem();
        if ("MoMo".equals(method) || "VietQR".equals(method)) {
            boolean paidSuccess = showQRPaymentDialog(method, total, table);
            if (!paidSuccess) return;
        }
        Result res = svc.checkout(table, new ArrayList<>(cart), method);
        StringBuilder msg = new StringBuilder();
        if (res.order() != null) {
            saveReceiptToFile(res.order());
            saveAllData();

            msg.append("Thanh toán thành công!\nTổng: ").append(fmt(res.order().total())).append("\nSố thứ tự của bạn: #").append(res.order().ticket);
        }
        if (!res.rejected().isEmpty()) {
            msg.append(res.order() == null ? "" : "\n\n").append("Rất tiếc, các món sau đã hết (nguyên liệu hoặc số phần) và chưa được tính tiền:");
            res.rejected().forEach(s -> msg.append("\n - ").append(s));
        }
        JOptionPane.showMessageDialog(frame, msg.toString());
        cart.clear();
        renderCart();
        refreshAll();
    }

    boolean showQRPaymentDialog(String method, long total, String table) {
        JDialog qrDialog = new JDialog(frame, "Thanh toán qua " + method, true);
        qrDialog.setSize(420, 580);
        qrDialog.setLocationRelativeTo(frame);
        JPanel contentPane = new JPanel(new BorderLayout(15, 15));
        contentPane.setBackground(BG);
        contentPane.setBorder(new EmptyBorder(20, 20, 20, 20));
        JLabel titleLbl = label("QUÉT MÃ " + method.toUpperCase() + " THANH TOÁN", Font.BOLD, 18, GOLD);
        titleLbl.setHorizontalAlignment(SwingConstants.CENTER);
        contentPane.add(titleLbl, BorderLayout.NORTH);
        JPanel centerPanel = new Card(new GridLayout(0, 1, 10, 10), CARD);
        JLabel amountLbl = label("Số tiền: " + fmt(total), Font.BOLD, 22, GREEN);
        amountLbl.setHorizontalAlignment(SwingConstants.CENTER);

        JLabel infoLbl = label("<html><div style='text-align:center;'>Nội dung CK: <b>Thanh toan " + table + "</b><br><font color='#A8968A'>Chủ TK: LE VAN A</font></div></html>", Font.PLAIN, 13, TEXT);
        infoLbl.setHorizontalAlignment(SwingConstants.CENTER);

        String fileName = method.equals("MoMo") ? "momo.png" : "bidv.png";
        JLabel qrImageLbl = new JLabel();
        qrImageLbl.setHorizontalAlignment(SwingConstants.CENTER);
        try {
            ImageIcon originalIcon = new ImageIcon(fileName);
            Image scaledImg = originalIcon.getImage().getScaledInstance(210, 210, Image.SCALE_SMOOTH);
            qrImageLbl.setIcon(new ImageIcon(scaledImg));
        } catch (Exception e) {
            qrImageLbl.setText("<html><font color='red'>Không tìm thấy file ảnh: " + fileName + "</font></html>");
        }
        centerPanel.add(amountLbl);
        centerPanel.add(qrImageLbl);
        centerPanel.add(infoLbl);
        contentPane.add(centerPanel, BorderLayout.CENTER);
        JPanel btnPanel = clear(new JPanel(new GridLayout(1, 2, 10, 0)));
        final boolean[] confirmed = {false};
        RoundButton cancelBtn = new RoundButton("Hủy", LINE, TEXT);
        cancelBtn.addActionListener(e -> qrDialog.dispose());
        RoundButton confirmBtn = new RoundButton("Đã chuyển khoản", GREEN, BG);
        confirmBtn.addActionListener(e -> {
            confirmed[0] = true;
            qrDialog.dispose();
        });
        btnPanel.add(cancelBtn);
        btnPanel.add(confirmBtn);
        contentPane.add(btnPanel, BorderLayout.SOUTH);
        qrDialog.setContentPane(contentPane);
        qrDialog.setVisible(true);
        return confirmed[0];
    }

    JPanel buildBar() {
        JPanel pg = page(new BorderLayout(0, 12));
        JList<String> list = new JList<>(barModel);
        pg.add(label("Khu vực Bếp / Pha chế - Đơn chờ", Font.BOLD, 22, TEXT), BorderLayout.NORTH);
        pg.add(scroll(list), BorderLayout.CENTER);
        pg.add(btn("Đã làm xong, gọi khách", GOLD, BG, () -> {
            int i = list.getSelectedIndex();
            if (i < 0) {
                JOptionPane.showMessageDialog(frame, "Chọn một đơn trong danh sách.");
                return;
            }
            Order o = svc.bar.remove(i);
            JOptionPane.showMessageDialog(frame, "Gọi khách: đơn #" + o.ticket + " (" + o.table + ")");
            refreshAll();
        }), BorderLayout.SOUTH);
        return pg;
    }

    static String ingName(String key) { return key.replaceAll("\\s*\\([^)]*\\)$", ""); }
    static String ingUnit(String key) { return key.matches(".*\\([^)]*\\)$") ? key.replaceAll("^.*\\(([^)]*)\\)$", "$1") : "cái"; }

    static class StockBar extends JComponent {
        final double pct; final Color color;
        StockBar(double pct, Color color) { this.pct = Math.max(0, Math.min(1, pct)); this.color = color; setPreferredSize(new Dimension(100, 18)); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight() - 2;
            g2.setColor(FLOOR); g2.fillRoundRect(0, 0, w, h, h, h);
            g2.setColor(LINE); g2.drawRoundRect(0, 0, w - 1, h - 1, h, h);
            if (pct > 0) { g2.setColor(color); g2.fillRoundRect(0, 0, Math.max(h, (int) (w * pct)), h, h, h); }
            g2.dispose();
        }
    }

    JComponent stockCard(String k) {
        int lv = svc.inv.level(k); Color c = lv == 0 ? RED : lv == 1 ? AMBER : GREEN;
        double[] s = svc.inv.stock.get(k); int pc = (int) Math.round(svc.inv.pct(k) * 100);
        Card card = new Card(new GridLayout(0, 1, 0, 6), CARD);
        JPanel head = clear(new JPanel(new BorderLayout()));
        head.add(label(ingName(k), Font.BOLD, 15, TEXT), BorderLayout.WEST);
        head.add(label(pc + "%", Font.BOLD, 18, c), BorderLayout.EAST);
        card.add(head);
        card.add(new StockBar(svc.inv.pct(k), c));
        card.add(label("Còn " + num(s[0]) + " / " + num(s[2]) + " " + ingUnit(k), Font.PLAIN, 13, MUTED));
        JPanel foot = clear(new JPanel(new BorderLayout()));
        foot.add(label(lv == 0 ? "THIẾU - cần nhập gấp" : lv == 1 ? "CẢNH BÁO - nên nhập" : "ĐẦY ĐỦ", Font.BOLD, 12, c), BorderLayout.WEST);
        RoundButton b = btn("Nhập thêm", lv == 2 ? LINE : GOLD, lv == 2 ? TEXT : BG, () -> restock(k));
        b.setPreferredSize(new Dimension(120, 34)); b.setFont(font(Font.BOLD, 13)); b.setMargin(new Insets(0, 4, 0, 4));
        foot.add(b, BorderLayout.EAST);
        card.add(foot);
        return card;
    }

    void restock(String k) {
        double[] s = svc.inv.stock.get(k); String unit = ingUnit(k), name = ingName(k);
        double room = s[2] - s[0];
        if (room <= 1e-9) { JOptionPane.showMessageDialog(frame, name + " đã đầy (100%)."); return; }
        JTextField in = new JTextField(10);
        JLabel preview = label("Sau khi nhập: -", Font.BOLD, 14, GOLD);
        in.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void upd() {
                try {
                    double q = Double.parseDouble(in.getText().trim().replace(',', '.'));
                    double after = Math.min(s[2], s[0] + Math.max(q, 0));
                    preview.setText("Sau khi nhập: " + Math.round(after / s[2] * 100) + "%  (" + num(after) + " / " + num(s[2]) + " " + unit + ")");
                } catch (NumberFormatException ex) { preview.setText("Sau khi nhập: -"); }
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { upd(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { upd(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { upd(); }
        });
        JPanel p = new JPanel(new GridLayout(0, 1, 4, 4));
        p.add(new JLabel("Hiện còn: " + num(s[0]) + " " + unit + " (" + Math.round(svc.inv.pct(k) * 100) + "%)"));
        p.add(new JLabel("Nhập thêm được tối đa: " + num(room) + " " + unit));
        p.add(new JLabel("Số lượng nhập (" + unit + "):")); p.add(in); p.add(preview);
        if (JOptionPane.showConfirmDialog(frame, p, "Nhập thêm - " + name, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        double q;
        try { q = Double.parseDouble(in.getText().trim().replace(',', '.')); } catch (NumberFormatException ex) { q = -1; }
        if (q <= 0) { JOptionPane.showMessageDialog(frame, "Số không hợp lệ."); return; }
        double add = Math.min(q, room);
        s[0] += add;
        saveInventoryToFile();
        refreshAll();
        if (q > room + 1e-9) JOptionPane.showMessageDialog(frame, "Kho chỉ chứa thêm được " + num(room) + " " + unit + ", đã nhập vừa đủ đầy kho.");
    }

    void refreshStock() {
        stockGrid.removeAll();
        List<String> keys = new ArrayList<>(svc.inv.stock.keySet());
        keys.sort(Comparator.comparingDouble(svc.inv::pct));
        int[] cnt = new int[3];
        for (String k : keys) { cnt[svc.inv.level(k)]++; stockGrid.add(stockCard(k)); }
        stockSummary.setText("<html><font color='#D9453D'>● Thiếu: " + cnt[0] + "</font> &nbsp;&nbsp; <font color='#F2C14E'>● Cảnh báo: " + cnt[1]
                + "</font> &nbsp;&nbsp; <font color='#3FBF6B'>● Đầy đủ: " + cnt[2] + "</font></html>");
        stockGrid.revalidate(); stockGrid.repaint();
    }

    JPanel buildStock() {
        JPanel pg = page(new BorderLayout(0, 12));
        JPanel top = clear(new JPanel(new BorderLayout(0, 4)));
        JPanel row = clear(new JPanel(new BorderLayout()));
        row.add(label("Kho nguyên liệu", Font.BOLD, 22, TEXT), BorderLayout.WEST);
        row.add(stockSummary, BorderLayout.EAST);
        top.add(row, BorderLayout.NORTH);
        int warn = (int) Math.round(WARN_PCT * 100);
        top.add(label("Đỏ: thiếu (≤ mức tối thiểu)   ·   Vàng: cảnh báo (dưới " + warn + "%)   ·   Xanh: đầy đủ (từ " + warn + "% trở lên)", Font.PLAIN, 13, MUTED), BorderLayout.CENTER);
        JPanel wrap = clear(new JPanel(new BorderLayout())); wrap.add(stockGrid, BorderLayout.NORTH);
        JScrollPane sp = scroll(wrap); sp.getViewport().setOpaque(false); sp.setOpaque(false); sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        pg.add(top, BorderLayout.NORTH); pg.add(sp, BorderLayout.CENTER);
        return pg;
    }

    JPanel buildReport() {
        JPanel pg = page(new BorderLayout(0, 14));
        JPanel kpis = clear(new JPanel(new GridLayout(1, 3, 14, 0)));
        String[] names = {"Doanh thu", "Số đơn", "Trung bình mỗi đơn"};
        JLabel[] vals = {kpiRevenue, kpiOrders, kpiAvg};
        for (int i = 0; i < 3; i++) {
            Card c = new Card(new GridLayout(0, 1, 0, 4), CARD);
            c.add(label(names[i], Font.PLAIN, 14, MUTED));
            c.add(vals[i]);
            kpis.add(c);
        }
        JPanel top = clear(new JPanel(new BorderLayout(0, 12)));
        top.add(label("Báo cáo kinh doanh", Font.BOLD, 22, TEXT), BorderLayout.NORTH);
        top.add(kpis, BorderLayout.CENTER);
        Card centerCard = new Card(new BorderLayout(0, 10), CARD);
        centerCard.add(chartPanel, BorderLayout.NORTH);
        reportArea.setEditable(false);
        reportArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        reportArea.setBorder(new EmptyBorder(10, 12, 10, 12));
        centerCard.add(scroll(reportArea), BorderLayout.CENTER);
        pg.add(top, BorderLayout.NORTH);
        pg.add(centerCard, BorderLayout.CENTER);
        return pg;
    }

    void refreshAll() {
        barModel.clear();
        for (Order o : svc.bar) {
            StringBuilder sb = new StringBuilder("<html><b><font color='#E0A94B'>#" + o.ticket + "</font> - " + o.table + "</b>");
            o.lines.forEach(l -> sb.append("<br>&nbsp;&nbsp;").append(l.describe()));
            barModel.addElement(sb.append("<br>&nbsp;</html>").toString());
        }
        if (barNavBtn != null) {
            int pendingCount = svc.bar.size();
            barNavBtn.setText(pendingCount > 0 ? "Bếp (" + pendingCount + ")" : "Bếp");
        }
        refreshStock();

        long revenue = svc.paid.stream().mapToLong(Order::total).sum();
        int n = svc.paid.size();

        kpiRevenue.setText(fmt(revenue));
        kpiOrders.setText(String.valueOf(n));
        kpiAvg.setText(fmt(n == 0 ? 0 : revenue / n));

        chartPanel.setData(svc.byHour, svc.guestsByHour);
        StringBuilder r = new StringBuilder("Món bán chạy:\n");
        svc.sold.entrySet().stream().sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                .forEach(e -> r.append("  ").append(e.getKey()).append(": ").append(e.getValue())
                        .append(foodNames.contains(e.getKey()) ? " phần\n" : " ly\n"));
        r.append("\nDoanh thu theo giờ:\n");
        svc.byHour.forEach((h, v) -> r.append(String.format(" %02d:00 - %02d:59 : %s\n", h, h, fmt(v))));
        reportArea.setText(r.toString());
        tiles.forEach(Component::repaint);
        refreshDetail();
    }

    public static void main(String[] args) {
        setupTheme();
        SwingUtilities.invokeLater(() -> new CafeApp().show());
    }
}