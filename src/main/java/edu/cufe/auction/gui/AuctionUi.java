package edu.cufe.auction.gui;

import edu.cufe.auction.account.PerformanceRank;
import edu.cufe.auction.agent.HumanBrokerAgent;
import edu.cufe.auction.config.SimulationConfig;
import edu.cufe.auction.engine.MatchingEngine;
import edu.cufe.auction.market.MarketDataListener;
import edu.cufe.auction.model.MarketSnapshot;
import edu.cufe.auction.model.OrderBookSnapshot;
import edu.cufe.auction.model.PriceLevel;
import edu.cufe.auction.model.Side;
import edu.cufe.auction.model.Trade;
import edu.cufe.auction.sim.SimulationRunner;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.beans.property.SimpleStringProperty;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * JavaFX 界面实现：盘口深度、最新价、下单面板与实时排行榜。
 *
 * <p>本类继承 {@link Application}，<b>不要直接运行它</b>（会报 <code>缺少 JavaFX 运行时组件</code>，
 * 原因见 {@link AuctionApp} 的类注释）。运行请用入口 {@link AuctionApp}。</p>
 *
 * <p>线程约定：所有 UI 更新都通过 {@link Platform#runLater(Runnable)} 切回 JavaFX 线程，
 * 业务线程只做数据准备。</p>
 */
public final class AuctionUi extends Application {

    private static final int DEPTH = 5;

    private SimulationRunner runner;
    private MatchingEngine engine;
    private final ObservableList<PriceLevel> bids = FXCollections.observableArrayList();
    private final ObservableList<PriceLevel> asks = FXCollections.observableArrayList();
    private final ObservableList<PerformanceRank> ranking = FXCollections.observableArrayList();
    private Label priceLabel;
    private Label statusLabel;
    private ComboBox<String> symbolBox;

    @Override
    public void start(Stage stage) throws Exception {
        SimulationConfig config = SimulationConfig.loadDefault();
        runner = new SimulationRunner(config);
        engine = runner.getEngine();

        runner.getPublisher().subscribe(new MarketDataListener() {
            @Override
            public void onTrade(Trade trade) {
                Platform.runLater(() -> statusLabel.setText("最近成交：" + trade));
            }

            @Override
            public void onMarketSnapshot(MarketSnapshot snapshot) {
                Platform.runLater(AuctionUi.this::refresh);
            }
        });
        runner.start();

        BorderPane root = new BorderPane();
        root.setTop(buildHeader(config));
        root.setCenter(buildDepthPane());
        root.setRight(buildOrderPane());
        root.setBottom(buildRankingPane());

        Scene scene = new Scene(root, 1180, 720);
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
    }

    private HBox buildHeader(SimulationConfig config) {
        symbolBox = new ComboBox<>(FXCollections.observableArrayList(engine.symbols()));
        symbolBox.setValue(engine.symbols().get(0));
        symbolBox.valueProperty().addListener((obs, old, now) -> refresh());
        priceLabel = new Label("—");
        priceLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");
        statusLabel = new Label("等待行情…");
        HBox box = new HBox(16, new Label("标的："), symbolBox, priceLabel, statusLabel);
        box.setPadding(new Insets(10));
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private HBox buildDepthPane() {
        VBox bidTable = levelTable("买盘（价格降序）", bids);
        VBox askTable = levelTable("卖盘（价格升序）", asks);
        HBox box = new HBox(12, bidTable, askTable);
        box.setPadding(new Insets(0, 10, 0, 10));
        return box;
    }

    private VBox levelTable(String title, ObservableList<PriceLevel> data) {
        TableView<PriceLevel> table = new TableView<>(data);
        table.setPlaceholder(new Label("无挂单"));
        TableColumn<PriceLevel, String> priceCol = new TableColumn<>("价格");
        priceCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getPrice().toPlainString()));
        TableColumn<PriceLevel, String> qtyCol = new TableColumn<>("数量");
        qtyCol.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getQuantity())));
        TableColumn<PriceLevel, String> countCol = new TableColumn<>("笔数");
        countCol.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getOrderCount())));
        table.getColumns().addAll(java.util.List.of(priceCol, qtyCol, countCol));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox wrapper = new VBox(4, new Label(title), table);
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(wrapper, javafx.scene.layout.Priority.ALWAYS);
        return wrapper;
    }

    private VBox buildOrderPane() {
        ComboBox<Side> sideBox = new ComboBox<>(FXCollections.observableArrayList(Side.BUY, Side.SELL));
        sideBox.setValue(Side.BUY);
        TextField priceField = new TextField();
        priceField.setPromptText("限价，如 175.50");
        TextField quantityField = new TextField();
        quantityField.setPromptText("数量，如 100");
        CheckBox marketCheck = new CheckBox("市价单");
        priceField.disableProperty().bind(marketCheck.selectedProperty());
        Label hint = new Label("下单方：" + (runner.firstHumanBroker() == null
                ? "未配置人类 broker" : runner.firstHumanBroker().getDisplayName()));
        Button submit = new Button("提交订单");
        hint.setWrapText(true);

        submit.setOnAction(e -> {
            HumanBrokerAgent broker = runner.firstHumanBroker();
            if (broker == null) {
                statusLabel.setText("未配置人类 broker，无法下单");
                return;
            }
            String symbol = symbolBox.getValue();
            try {
                long quantity = Long.parseLong(quantityField.getText().trim());
                if (marketCheck.isSelected()) {
                    broker.placeMarketOrder(symbol, sideBox.getValue(), quantity);
                    statusLabel.setText("已提交市价单：" + sideBox.getValue() + " " + quantity + " 股");
                } else {
                    BigDecimal price = new BigDecimal(priceField.getText().trim());
                    broker.placeLimitOrder(symbol, sideBox.getValue(), price, quantity);
                    statusLabel.setText("已提交限价单：" + sideBox.getValue() + " " + quantity
                            + " 股 @ " + price.toPlainString());
                }
            } catch (RuntimeException ex) {
                statusLabel.setText("下单失败：" + ex.getMessage());
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.add(new Label("方向"), 0, 0);
        grid.add(sideBox, 1, 0);
        grid.add(new Label("价格"), 0, 1);
        grid.add(priceField, 1, 1);
        grid.add(new Label("数量"), 0, 2);
        grid.add(quantityField, 1, 2);
        grid.add(marketCheck, 1, 3);
        grid.add(submit, 1, 4);
        VBox box = new VBox(10, new Label("手动下单"), grid, hint);
        box.setPadding(new Insets(10));
        box.setPrefWidth(260);
        return box;
    }

    private VBox buildRankingPane() {
        TableView<PerformanceRank> table = new TableView<>(ranking);
        table.setPlaceholder(new Label("等待账户数据"));
        table.setPrefHeight(200);
        TableColumn<PerformanceRank, String> rankCol = new TableColumn<>("名次");
        rankCol.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().getRank())));
        TableColumn<PerformanceRank, String> nameCol = new TableColumn<>("agent");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getDisplayName()));
        TableColumn<PerformanceRank, String> equityCol = new TableColumn<>("总权益");
        equityCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getEquity().toPlainString()));
        TableColumn<PerformanceRank, String> returnCol = new TableColumn<>("收益率%");
        returnCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getReturnRate()
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString()));
        TableColumn<PerformanceRank, String> ddCol = new TableColumn<>("最大回撤%");
        ddCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getMaxDrawdown()
                .setScale(2, RoundingMode.HALF_UP).toPlainString()));
        TableColumn<PerformanceRank, String> winCol = new TableColumn<>("胜率%");
        winCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getWinRate()
                .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString()));
        table.getColumns().addAll(java.util.List.of(rankCol, nameCol, equityCol, returnCol, ddCol, winCol));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        VBox box = new VBox(6, new Label("实时排行榜"), table);
        box.setPadding(new Insets(10));
        return box;
    }

    /** 刷新盘口、最新价与排行榜（必须在 JavaFX 线程调用）。 */
    private void refresh() {
        String symbol = symbolBox == null ? null : symbolBox.getValue();
        if (symbol == null || engine == null) {
            return;
        }
        OrderBookSnapshot book = engine.snapshot(symbol, DEPTH);
        if (book != null) {
            bids.setAll(book.getBids());
            asks.setAll(book.getAsks());
            priceLabel.setText(String.format("%s  最新 %s  买一 %s / 卖一 %s",
                    symbol,
                    book.getLastPrice() == null ? "—" : book.getLastPrice().toPlainString(),
                    book.getBestBid() == null ? "—" : book.getBestBid().toPlainString(),
                    book.getBestAsk() == null ? "—" : book.getBestAsk().toPlainString()));
        }
        ranking.setAll(runner.getAccountManager().ranking(engine.lastPrices()));
    }

    @Override
    public void stop() {
        if (runner != null) {
            runner.close();
        }
    }
}
