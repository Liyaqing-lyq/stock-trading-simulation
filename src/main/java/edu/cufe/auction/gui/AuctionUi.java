package edu.cufe.auction.gui;

import edu.cufe.auction.account.Account;
import edu.cufe.auction.account.PerformanceRank;
import edu.cufe.auction.account.Position;
import edu.cufe.auction.agent.HumanBrokerAgent;
import edu.cufe.auction.config.SimulationConfig;
import edu.cufe.auction.engine.MatchingEngine;
import edu.cufe.auction.market.MarketDataListener;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.Order;
import edu.cufe.auction.model.OrderBookSnapshot;
import edu.cufe.auction.model.PriceLevel;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.model.Trade;
import edu.cufe.auction.sim.SimulationRunner;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * JavaFX 界面实现：行情深度、价格走势、手动下单、账户与持仓、我的挂单、实时排行榜。
 *
 * <p>本类继承 {@link Application}，<b>不要直接运行它</b>（会报 <code>缺少 JavaFX 运行时组件</code>，
 * 原因见 {@link AuctionApp} 的类注释）。运行请用入口 {@link AuctionApp}。</p>
 *
 * <p>线程约定：业务线程只把事件丢进 {@link Platform#runLater(Runnable)}，所有控件访问都发生在
 * JavaFX 线程；500ms 定时器只做读取与刷新，不持有引擎锁。</p>
 *
 * <p>配色口径：涨/买=红、跌/卖=绿（中国市场习惯）。</p>
 */
public final class AuctionUi extends Application {

    /** 盘口展示档位数。 */
    private static final int DEPTH = 5;
    /** 成交明细保留条数。 */
    private static final int MAX_TRADES = 60;
    /** 走势图最多保留的点数。 */
    private static final int MAX_POINTS = 240;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private SimulationRunner runner;
    private MatchingEngine engine;
    private Account account;
    private HumanBrokerAgent broker;

    // ---- 数据源 ----
    private final ObservableList<PriceLevel> bids = FXCollections.observableArrayList();
    private final ObservableList<PriceLevel> asks = FXCollections.observableArrayList();
    private final ObservableList<PerformanceRank> ranking = FXCollections.observableArrayList();
    private final ObservableList<TradeRow> trades = FXCollections.observableArrayList();
    private final ObservableList<PosRow> positions = FXCollections.observableArrayList();
    private final ObservableList<Order> myOrders = FXCollections.observableArrayList();
    private final XYChart.Series<String, Number> priceSeries = new XYChart.Series<>();

    // ---- 控件引用（刷新时更新） ----
    private ComboBox<String> symbolBox;
    private Label priceLabel;
    private Label changeLabel;
    private Label bidLabel;
    private Label askLabel;
    private Label volumeLabel;
    private Label cashLabel;
    private Label frozenLabel;
    private Label equityLabel;
    private Label returnLabel;
    private Label realizedLabel;
    private Label winLabel;
    private Label brokerLabel;
    private Label errorLabel;
    private Label statusLabel;
    private LineChart<String, Number> priceChart;
    private TabPane tabs;
    private TextField priceField;
    private TextField quantityField;
    private CheckBox marketCheck;
    private ToggleGroup sideGroup;
    private String chartSymbol;
    private BigDecimal lastChartPrice;

    @Override
    public void start(Stage stage) throws Exception {
        SimulationConfig config = SimulationConfig.loadDefault();
        runner = new SimulationRunner(config);
        engine = runner.getEngine();
        broker = runner.firstHumanBroker();
        account = broker == null ? null : broker.getAccount();

        runner.getPublisher().subscribe(new MarketDataListener() {
            @Override
            public void onTrade(Trade trade) {
                Platform.runLater(() -> onTradeReceived(trade));
            }

            @Override
            public void onMarketSnapshot(MarketSnapshot snapshot) {
                Platform.runLater(AuctionUi.this::refresh);
            }
        });
        runner.start();

        BorderPane root = new BorderPane();
        root.setTop(buildHeader());
        root.setCenter(buildCenter());
        root.setBottom(buildBottom());

        Scene scene = new Scene(root, 1360, 840);
        scene.getStylesheets().add(
                AuctionUi.class.getResource("/auction.css").toExternalForm());
        stage.setTitle("多智能体连续竞价股票交易模拟系统");
        stage.setScene(scene);
        stage.show();

        Timeline timeline = new Timeline(new KeyFrame(Duration.millis(500), e -> refresh()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
        refresh();

        System.out.printf("[AuctionUi] 界面已启动：标的 %s，agent %d 个，数据目录 %s%n",
                engine.symbols(), runner.getAgents().size(),
                runner.getRecorder().getDirectory().toAbsolutePath());
        installSnapshotHook(scene);
    }

    // ------------------------------------------------------------------
    // 界面骨架
    // ------------------------------------------------------------------

    private HBox buildHeader() {
        symbolBox = new ComboBox<>(FXCollections.observableArrayList(engine.symbols()));
        symbolBox.setValue(engine.symbols().get(0));
        symbolBox.valueProperty().addListener((obs, old, now) -> {
            priceSeries.getData().clear();
            chartSymbol = null;
            lastChartPrice = null;
            refresh();
        });

        priceLabel = new Label("—");
        priceLabel.getStyleClass().add("price-big");
        changeLabel = new Label("—");
        changeLabel.getStyleClass().add("mono");
        bidLabel = new Label("—");
        askLabel = new Label("—");
        volumeLabel = new Label("—");

        HBox header = new HBox(12,
                fieldLabel("标的"), symbolBox,
                priceLabel, framed(changeLabel),
                fieldLabel("买一"), framed(bidLabel),
                fieldLabel("卖一"), framed(askLabel),
                fieldLabel("成交量"), framed(volumeLabel),
                spacer(),
                framed(new Label("● 连续竞价中")));
        header.getStyleClass().add("header");
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    /** 把控件包进半透明小胶囊里，用于深色顶栏。 */
    private HBox framed(javafx.scene.Node node) {
        HBox box = new HBox(node);
        box.getStyleClass().add("chip");
        box.setAlignment(Pos.CENTER);
        return box;
    }

    private Region spacer() {
        Region region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private HBox buildCenter() {
        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("行情深度", buildDepthTab()),
                new Tab("价格走势", buildChartTab()),
                new Tab("我的挂单", buildMyOrdersTab()));
        this.tabs = tabs;
        HBox.setHgrow(tabs, Priority.ALWAYS);

        // 右列与中间区等高：把持仓放在右列底部并让它撑满，避免下方出现大片空白
        VBox positionsCard = buildPositionsCard();
        VBox right = new VBox(10, buildAccountCard(), buildOrderFormCard(), positionsCard);
        VBox.setVgrow(positionsCard, Priority.ALWAYS);
        right.setPrefWidth(286);
        right.setMinWidth(286);

        HBox center = new HBox(10, tabs, right);
        center.setPadding(new Insets(10, 10, 4, 10));
        return center;
    }

    private VBox buildDepthTab() {
        VBox bidBox = card("买盘（价格降序）", levelTable(bids, "side-buy"));
        VBox askBox = card("卖盘（价格升序）", levelTable(asks, "side-sell"));
        HBox depth = new HBox(10, bidBox, askBox);
        HBox.setHgrow(bidBox, Priority.ALWAYS);
        HBox.setHgrow(askBox, Priority.ALWAYS);

        TableView<TradeRow> tradeTable = new TableView<>(trades);
        tradeTable.setPlaceholder(new Label("暂无成交"));
        tradeTable.getColumns().addAll(List.of(
                textColumn("时间", TradeRow::time, 90),
                textColumn("价格", TradeRow::price, 90),
                textColumn("数量", TradeRow::quantity, 80),
                textColumn("买方", TradeRow::buyer, 110),
                textColumn("卖方", TradeRow::seller, 110)));
        tradeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox tradeCard = card("成交明细（最近 " + MAX_TRADES + " 笔）", tradeTable);
        VBox.setVgrow(tradeTable, Priority.ALWAYS);

        VBox box = new VBox(10, depth, tradeCard);
        VBox.setVgrow(tradeCard, Priority.ALWAYS);
        box.setPadding(new Insets(10));
        return box;
    }

    private VBox buildChartTab() {
        priceChart = new LineChart<>(new CategoryAxis(), new NumberAxis());
        priceChart.setAnimated(false);
        priceChart.setCreateSymbols(false);
        priceChart.setLegendVisible(false);
        priceChart.getData().add(priceSeries);
        priceChart.getStyleClass().add("mono");
        priceChart.setMinHeight(360);

        Label hint = new Label("价格由订单碰撞产生：曲线上的每个点都是一次行情更新后的最新成交价。");
        hint.getStyleClass().add("muted");

        VBox card = card("最新价走势（随选中标的切换）", priceChart, hint);
        VBox.setVgrow(priceChart, Priority.ALWAYS);
        VBox box = new VBox(card);
        VBox.setVgrow(card, Priority.ALWAYS);
        box.setPadding(new Insets(10));
        return box;
    }

    private VBox buildMyOrdersTab() {
        TableView<Order> orderTable = new TableView<>(myOrders);
        orderTable.setPlaceholder(new Label("没有未成交的挂单"));
        TableColumn<Order, Void> actionColumn = new TableColumn<>("操作");
        actionColumn.setPrefWidth(70);
        actionColumn.setCellFactory(col -> new TableCell<>() {
            private final Button cancelButton = new Button("撤单");

            {
                cancelButton.getStyleClass().add("ghost");
                cancelButton.setOnAction(e -> {
                    Order order = getTableView().getItems().get(getIndex());
                    broker.requestCancel(order.getOrderId());
                    statusLabel.setText("已提交撤单请求：" + order);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setGraphic(empty ? null : cancelButton);
            }
        });
        orderTable.getColumns().addAll(List.of(
                textColumn("标的", Order::getSymbol, 70),
                textColumn("方向", o -> o.getSide() == Side.BUY ? "买入" : "卖出", 60),
                textColumn("价格", o -> o.getLimitPrice() == null ? "市价" : o.getLimitPrice().toPlainString(), 90),
                textColumn("数量", o -> String.valueOf(o.getQuantity()), 70),
                textColumn("已成交", o -> String.valueOf(o.getFilledQuantity()), 70),
                textColumn("剩余", o -> String.valueOf(o.getRemainingQuantity()), 70),
                actionColumn));
        orderTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        orderTable.setMaxWidth(Double.MAX_VALUE);

        Label hint = new Label("撤单会在下一个决策周期提交给撮合引擎；"
                + "撤单成功后冻结的资金/持仓立即释放。");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        VBox orderCard = card("我的挂单（可撤单）", orderTable, hint);
        VBox.setVgrow(orderTable, Priority.ALWAYS);
        VBox box = new VBox(orderCard);
        VBox.setVgrow(orderCard, Priority.ALWAYS);
        box.setPadding(new Insets(10));
        return box;
    }

    private VBox buildPositionsCard() {
        TableView<PosRow> positionTable = new TableView<>(positions);
        positionTable.setPlaceholder(new Label("当前无持仓"));
        positionTable.getColumns().addAll(List.of(
                textColumn("标的", PosRow::symbol, 60),
                textColumn("持仓", PosRow::quantity, 55),
                textColumn("可卖", PosRow::available, 55),
                textColumn("成本", PosRow::averageCost, 75),
                textColumn("最新价", PosRow::lastPrice, 75),
                numberColumn("浮动盈亏", PosRow::unrealizedPnl, 90)));
        positionTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        positionTable.setMaxWidth(Double.MAX_VALUE);
        VBox card = card("当前持仓", positionTable);
        VBox.setVgrow(positionTable, Priority.ALWAYS);
        return card;
    }

    private VBox buildAccountCard() {
        cashLabel = valueLabel();
        frozenLabel = valueLabel();
        equityLabel = valueLabel();
        returnLabel = valueLabel();
        realizedLabel = valueLabel();
        winLabel = valueLabel();
        brokerLabel = new Label(broker == null ? "未配置人类 broker" : broker.getDisplayName());
        brokerLabel.getStyleClass().add("muted");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(5);
        ColumnConstraints left = new ColumnConstraints();
        ColumnConstraints right = new ColumnConstraints();
        right.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(left, right);
        int row = 0;
        addRow(grid, row++, "下单账户", brokerLabel);
        addRow(grid, row++, "可用现金", cashLabel);
        addRow(grid, row++, "冻结资金", frozenLabel);
        addRow(grid, row++, "总权益", equityLabel);
        addRow(grid, row++, "收益率", returnLabel);
        addRow(grid, row++, "已实现盈亏", realizedLabel);
        addRow(grid, row, "胜率", winLabel);
        return card("账户概览", grid);
    }

    private VBox buildOrderFormCard() {
        sideGroup = new ToggleGroup();
        RadioButton buyRadio = new RadioButton("买入");
        RadioButton sellRadio = new RadioButton("卖出");
        buyRadio.setToggleGroup(sideGroup);
        sellRadio.setToggleGroup(sideGroup);
        buyRadio.setSelected(true);
        buyRadio.getStyleClass().add("side-buy");
        sellRadio.getStyleClass().add("side-sell");
        HBox sideBox = new HBox(14, buyRadio, sellRadio);

        priceField = new TextField();
        priceField.setPromptText("如 175.50");
        quantityField = new TextField();
        quantityField.setPromptText("如 100（整数股）");

        marketCheck = new CheckBox("市价单（立即成交，剩余自动撤销）");
        priceField.disableProperty().bind(marketCheck.selectedProperty());

        Button submitButton = new Button("提交订单");
        submitButton.getStyleClass().add("primary");
        submitButton.setMaxWidth(Double.MAX_VALUE);
        submitButton.setDefaultButton(true);
        submitButton.setOnAction(e -> submitOrder());

        errorLabel = new Label();
        errorLabel.getStyleClass().add("error-text");
        errorLabel.setWrapText(true);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(7);
        ColumnConstraints left = new ColumnConstraints();
        ColumnConstraints right = new ColumnConstraints();
        right.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(left, right);
        addRow(grid, 0, "方向", sideBox);
        addRow(grid, 1, "价格", priceField);
        addRow(grid, 2, "数量", quantityField);
        grid.add(marketCheck, 1, 3);
        grid.add(submitButton, 1, 4);

        Label tip = new Label("提示：输入框内按 Enter 可直接提交。");
        tip.getStyleClass().add("muted");
        return card("手动下单", grid, errorLabel, tip);
    }

    private VBox buildBottom() {
        TableView<PerformanceRank> rankTable = new TableView<>(ranking);
        rankTable.setPlaceholder(new Label("等待账户数据"));
        rankTable.setPrefHeight(190);
        rankTable.getColumns().addAll(List.of(
                textColumn("名次", r -> String.valueOf(r.getRank()), 60),
                textColumn("交易员", PerformanceRank::getDisplayName, 150),
                textColumn("初始权益", r -> r.getInitialBalance().toPlainString(), 110),
                textColumn("期末权益", r -> r.getEquity().toPlainString(), 110),
                percentColumn("收益率", PerformanceRank::getReturnRate, 100),
                numberColumn("已实现盈亏", PerformanceRank::getRealizedPnl, 110),
                percentColumn("最大回撤", PerformanceRank::getMaxDrawdown, 100),
                textColumn("平仓笔数", r -> String.valueOf(r.getClosedTradeCount()), 80),
                percentColumn("胜率", PerformanceRank::getWinRate, 90)));
        rankTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        statusLabel = new Label("就绪");
        statusLabel.getStyleClass().add("muted");
        HBox statusBar = new HBox(statusLabel);
        statusBar.getStyleClass().add("status-bar");

        VBox box = new VBox(card("实时排行榜（按收益率）", rankTable), statusBar);
        box.setPadding(new Insets(0, 10, 0, 10));
        return box;
    }

    private void addRow(GridPane grid, int row, String key, javafx.scene.Node value) {
        Label label = new Label(key);
        label.getStyleClass().add("key-label");
        grid.add(label, 0, row);
        grid.add(value, 1, row);
    }

    private Label valueLabel() {
        Label label = new Label("—");
        label.getStyleClass().add("mono");
        return label;
    }

    private VBox card(String title, javafx.scene.Node... children) {
        VBox box = new VBox(6);
        box.getStyleClass().add("card");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("card-title");
        box.getChildren().add(titleLabel);
        box.getChildren().addAll(children);
        return box;
    }

    private TableView<PriceLevel> levelTable(ObservableList<PriceLevel> data, String accentClass) {
        TableView<PriceLevel> table = new TableView<>(data);
        table.setPlaceholder(new Label("暂无挂单"));
        TableColumn<PriceLevel, String> priceColumn = textColumn("价格",
                l -> l.getPrice().toPlainString(), 90);
        priceColumn.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().removeAll("side-buy", "side-sell", "mono");
                if (!empty) {
                    getStyleClass().addAll("mono", accentClass);
                }
            }
        });
        table.getColumns().addAll(List.of(
                priceColumn,
                textColumn("数量", l -> String.valueOf(l.getQuantity()), 80),
                textColumn("笔数", l -> String.valueOf(l.getOrderCount()), 60)));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(190);
        return table;
    }

    private <S> TableColumn<S, String> textColumn(String title, Function<S, String> getter, double width) {
        TableColumn<S, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    /** 金额列：等宽字体 + 涨红跌绿。 */
    private <S> TableColumn<S, BigDecimal> numberColumn(String title, Function<S, BigDecimal> getter,
                                                       double width) {
        TableColumn<S, BigDecimal> column = new TableColumn<>(title);
        column.setCellValueFactory(cd -> new SimpleObjectProperty<>(getter.apply(cd.getValue())));
        column.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(BigDecimal value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeAll("mono", "up", "down", "flat");
                if (empty || value == null) {
                    setText(null);
                    return;
                }
                BigDecimal shown = value.signum() == 0 ? BigDecimal.ZERO : value;
                setText(shown.setScale(2, RoundingMode.HALF_UP).toPlainString());
                getStyleClass().add("mono");
                getStyleClass().add(shown.signum() > 0 ? "up" : shown.signum() < 0 ? "down" : "flat");
            }
        });
        column.setPrefWidth(width);
        return column;
    }

    /** 比例列（0.05 显示为 5.00%）。 */
    private <S> TableColumn<S, BigDecimal> percentColumn(String title, Function<S, BigDecimal> getter,
                                                         double width) {
        return numberColumn(title, s -> getter.apply(s)
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP), width);
    }

    // ------------------------------------------------------------------
    // 刷新与交互
    // ------------------------------------------------------------------

    private void refresh() {
        String symbol = symbolBox == null ? null : symbolBox.getValue();
        if (symbol == null) {
            return;
        }
        Map<String, BigDecimal> lastPrices = engine.lastPrices();
        MarketSnapshot market = engine.marketSnapshot(symbol, DEPTH);
        OrderBookSnapshot book = engine.snapshot(symbol, DEPTH);

        if (book != null) {
            bids.setAll(book.getBids());
            asks.setAll(book.getAsks());
        }
        if (market != null) {
            priceLabel.setText(market.getLastPrice() == null ? "—" : market.getLastPrice().toPlainString());
            BigDecimal change = market.getChangePercent();
            changeLabel.setText(String.format("%+.2f%%", change.doubleValue()));
            styleBySign(changeLabel, change);
            bidLabel.setText(market.getBestBid() == null ? "—" : market.getBestBid().toPlainString());
            askLabel.setText(market.getBestAsk() == null ? "—" : market.getBestAsk().toPlainString());
            volumeLabel.setText(String.valueOf(market.getCumulativeVolume()));
            appendPricePoint(symbol, market);
        }
        refreshAccount(lastPrices);
        refreshPositions(lastPrices);
        refreshMyOrders();
        ranking.setAll(runner.getAccountManager().ranking(lastPrices));
        statusLabel.setText(String.format("市场：连续竞价中　|　最后更新 %s　|　我的委托 %d 笔　|　监听异常 %d 次",
                CLOCK.format(Instant.now()),
                broker == null ? 0 : broker.getManualOrderCount(),
                runner.getPublisher().getListenerErrorCount()));
    }

    private void refreshAccount(Map<String, BigDecimal> lastPrices) {
        if (account == null) {
            return;
        }
        BigDecimal equity = account.equity(lastPrices);
        BigDecimal returnRate = account.returnRate(lastPrices);
        cashLabel.setText(account.getAvailableCash().toPlainString());
        frozenLabel.setText(account.getFrozenCash().toPlainString());
        equityLabel.setText(equity.toPlainString());
        returnLabel.setText(String.format("%+.2f%%", returnRate.doubleValue() * 100));
        styleBySign(returnLabel, returnRate);
        BigDecimal realized = account.getPerformance().getRealizedPnl();
        realizedLabel.setText(realized.toPlainString());
        styleBySign(realizedLabel, realized);
        winLabel.setText(account.getPerformance().getWinRate()
                .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP) + "%");
    }

    private void refreshPositions(Map<String, BigDecimal> lastPrices) {
        if (account == null) {
            positions.clear();
            return;
        }
        List<PosRow> rows = new ArrayList<>();
        for (Position position : account.getPositions().values()) {
            BigDecimal last = lastPrices.getOrDefault(position.getSymbol(), position.getAverageCost());
            rows.add(new PosRow(position.getSymbol(),
                    String.valueOf(position.getQuantity()),
                    String.valueOf(position.getAvailableQuantity()),
                    position.getAverageCost().toPlainString(),
                    last.toPlainString(),
                    position.unrealizedPnl(last)));
        }
        positions.setAll(rows);
    }

    private void refreshMyOrders() {
        if (broker == null) {
            myOrders.clear();
            return;
        }
        myOrders.setAll(engine.allRestingOrders().stream()
                .filter(o -> broker.getAgentId().equals(o.getAgentId()))
                .toList());
    }

    private void onTradeReceived(Trade trade) {
        trades.add(0, new TradeRow(CLOCK.format(Instant.ofEpochMilli(trade.getTimestampMillis())),
                trade.getPrice().toPlainString(),
                String.valueOf(trade.getQuantity()),
                trade.getBuyAgentId(),
                trade.getSellAgentId()));
        while (trades.size() > MAX_TRADES) {
            trades.remove(trades.size() - 1);
        }
    }

    /** 最新价变化时向走势图追加一个点（相同价格不重复追加，避免平线噪声）。 */
    private void appendPricePoint(String symbol, MarketSnapshot market) {
        BigDecimal last = market.getLastPrice();
        if (last == null) {
            return;
        }
        if (!symbol.equals(chartSymbol)) {
            priceSeries.getData().clear();
            chartSymbol = symbol;
            lastChartPrice = null;
        }
        if (lastChartPrice != null && last.compareTo(lastChartPrice) == 0) {
            return;
        }
        lastChartPrice = last;
        priceSeries.getData().add(new XYChart.Data<>(
                CLOCK.format(Instant.ofEpochMilli(market.getTimestampMillis())), last));
        while (priceSeries.getData().size() > MAX_POINTS) {
            priceSeries.getData().remove(0);
        }
    }

    private void submitOrder() {
        errorLabel.setText("");
        if (broker == null) {
            errorLabel.setText("未配置人类 broker，无法下单");
            return;
        }
        Side side = ((RadioButton) sideGroup.getSelectedToggle()).getText().equals("买入")
                ? Side.BUY : Side.SELL;
        String symbol = symbolBox.getValue();
        try {
            long quantity = Long.parseLong(quantityField.getText().trim());
            if (quantity <= 0) {
                throw new NumberFormatException("数量必须为正");
            }
            if (marketCheck.isSelected()) {
                broker.placeMarketOrder(symbol, side, quantity);
                statusLabel.setText(String.format("已提交市价单：%s %s %d 股", side, symbol, quantity));
            } else {
                BigDecimal price = new BigDecimal(priceField.getText().trim());
                if (price.signum() <= 0) {
                    throw new NumberFormatException("价格必须为正");
                }
                broker.placeLimitOrder(symbol, side, price, quantity);
                statusLabel.setText(String.format("已提交限价单：%s %s %d 股 @ %s",
                        side, symbol, quantity, price.toPlainString()));
            }
        } catch (NumberFormatException | NullPointerException ex) {
            errorLabel.setText("输入有误：" + ex.getMessage());
        } catch (IllegalArgumentException ex) {
            errorLabel.setText("下单失败：" + ex.getMessage());
        }
    }

    private void styleBySign(Label label, BigDecimal value) {
        label.getStyleClass().removeAll("up", "down", "flat");
        label.getStyleClass().add(value.signum() > 0 ? "up" : value.signum() < 0 ? "down" : "flat");
    }

    /**
     * 开发/自检用的截图钩子：加 {@code -Dauction.snapshot=<png 路径>}（可选
     * {@code -Dauction.snapshot.delay=毫秒}）运行后，界面会截图存盘并退出。
     *
     * <p>用于在无法交互的环境下核对界面渲染结果，不影响正常启动路径。</p>
     */
    private void installSnapshotHook(Scene scene) {
        String path = System.getProperty("auction.snapshot");
        if (path == null || path.isBlank()) {
            return;
        }
        long delay = Long.getLong("auction.snapshot.delay", 6000L);
        String tabIndex = System.getProperty("auction.tab");
        if (tabIndex != null && tabs != null) {
            int index = Integer.parseInt(tabIndex);
            if (index >= 0 && index < tabs.getTabs().size()) {
                tabs.getSelectionModel().select(index);
            }
        }
        PauseTransition pause = new PauseTransition(Duration.millis(delay));
        pause.setOnFinished(e -> {
            try {
                writePng(scene.snapshot(null), new File(path));
                System.out.println("[AuctionUi] 界面截图已保存：" + path);
            } catch (IOException | RuntimeException ex) {
                System.err.println("[AuctionUi] 界面截图失败：" + ex);
            } finally {
                Platform.exit();
            }
        });
        pause.play();
    }

    private void writePng(WritableImage image, File file) throws IOException {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        PixelReader reader = image.getPixelReader();
        int[] pixels = new int[width * height];
        reader.getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width);
        buffered.setRGB(0, 0, width, height, pixels, 0, width);
        ImageIO.write(buffered, "png", file);
    }

    @Override
    public void stop() {
        if (runner != null) {
            runner.close();
        }
    }

    // ------------------------------------------------------------------
    // 行模型
    // ------------------------------------------------------------------

    /** 成交明细行。 */
    private record TradeRow(String time, String price, String quantity, String buyer, String seller) {
    }

    /** 持仓行。 */
    private record PosRow(String symbol, String quantity, String available, String averageCost,
                          String lastPrice, BigDecimal unrealizedPnl) {
    }
}
