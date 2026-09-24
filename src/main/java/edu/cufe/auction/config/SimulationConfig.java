package edu.cufe.auction.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 模拟参数（读取 {@code config.properties}）。
 *
 * <p>支持 {@code ${ENV_NAME}} 形式的环境变量占位，例如
 * {@code llm.api.key=${LLM_API_KEY}}，避免把密钥写进仓库。</p>
 */
public final class SimulationConfig {

    /** classpath 上的默认配置文件。 */
    public static final String DEFAULT_RESOURCE = "config.properties";

    private final Properties props = new Properties();

    private SimulationConfig(Properties source) {
        props.putAll(source);
    }

    /**
     * 从 classpath 读取默认配置。
     *
     * @return 配置对象
     * @throws IOException 资源缺失或读取失败
     */
    public static SimulationConfig loadDefault() throws IOException {
        try (InputStream in = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(DEFAULT_RESOURCE)) {
            if (in == null) {
                throw new IOException("classpath 上找不到 " + DEFAULT_RESOURCE);
            }
            return load(in);
        }
    }

    /**
     * 从文件读取配置。
     *
     * @param path 配置文件路径
     * @return 配置对象
     * @throws IOException 读取失败
     */
    public static SimulationConfig load(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return load(reader);
        }
    }

    private static SimulationConfig load(InputStream in) throws IOException {
        Properties p = new Properties();
        p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        return new SimulationConfig(p);
    }

    private static SimulationConfig load(Reader reader) throws IOException {
        Properties p = new Properties();
        p.load(reader);
        return new SimulationConfig(p);
    }

    /**
     * 展开 {@code ${ENV}} 占位。
     *
     * @param raw 原始值
     * @return 展开后的值；环境变量不存在时返回空串
     */
    private String resolve(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.startsWith("${") && value.endsWith("}")) {
            String env = System.getenv(value.substring(2, value.length() - 1));
            return env == null ? "" : env;
        }
        return value;
    }

    private String getString(String key, String defaultValue) {
        return resolve(props.getProperty(key, defaultValue));
    }

    private int getInt(String key, int defaultValue) {
        String v = getString(key, null);
        return v == null || v.isBlank() ? defaultValue : Integer.parseInt(v);
    }

    private long getLong(String key, long defaultValue) {
        String v = getString(key, null);
        return v == null || v.isBlank() ? defaultValue : Long.parseLong(v);
    }

    private double getDouble(String key, double defaultValue) {
        String v = getString(key, null);
        return v == null || v.isBlank() ? defaultValue : Double.parseDouble(v);
    }

    private BigDecimal getDecimal(String key, BigDecimal defaultValue) {
        String v = getString(key, null);
        return v == null || v.isBlank() ? defaultValue : new BigDecimal(v);
    }

    /** @return 时间倍率 */
    public double getSpeed() {
        return getDouble("simulation.speed", 1.0);
    }

    /** @return 模拟总时长（秒） */
    public int getDurationSeconds() {
        return getInt("simulation.duration", 300);
    }

    /** @return 定时器 tick 间隔（毫秒） */
    public long getTickIntervalMillis() {
        return getLong("simulation.tick.interval.ms", 1000);
    }

    /** @return 标的 → 参考价（保持配置顺序） */
    public Map<String, BigDecimal> getStocks() {
        String symbols = getString("stocks", "");
        Map<String, BigDecimal> stocks = new LinkedHashMap<>();
        for (String symbol : symbols.split(",")) {
            String trimmed = symbol.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            BigDecimal price = getDecimal(trimmed, null);
            if (price == null) {
                throw new IllegalStateException("配置缺少标的价格：" + trimmed);
            }
            stocks.put(trimmed, price);
        }
        if (stocks.isEmpty()) {
            throw new IllegalStateException("配置 stocks 为空");
        }
        return stocks;
    }

    /** @return 人类 broker 数量 */
    public int getHumanCount() {
        return getInt("agent.human.count", 1);
    }

    /** @return 人类 broker 初始资金 */
    public BigDecimal getHumanInitialBalance() {
        return getDecimal("agent.human.initial.balance", new BigDecimal("100000"));
    }

    /** @return AI 交易员数量 */
    public int getAiCount() {
        return getInt("agent.ai.count", 0);
    }

    /** @return AI 交易员初始资金 */
    public BigDecimal getAiInitialBalance() {
        return getDecimal("agent.ai.initial.balance", new BigDecimal("50000"));
    }

    /** @return AI 决策间隔（秒） */
    public int getAiDecisionIntervalSeconds() {
        return getInt("agent.ai.decision.interval", 3);
    }

    /** @return 噪音/做市 agent 数量 */
    public int getNoiseCount() {
        return getInt("agent.noise.count", 0);
    }

    /** @return 噪音 agent 初始资金 */
    public BigDecimal getNoiseInitialBalance() {
        return getDecimal("agent.noise.initial.balance", new BigDecimal("80000"));
    }

    /** @return LLM 接口地址 */
    public String getLlmEndpoint() {
        return getString("llm.endpoint", "https://api.deepseek.com/v1/chat/completions");
    }

    /** @return LLM 模型名 */
    public String getLlmModel() {
        return getString("llm.model", "deepseek-chat");
    }

    /** @return LLM 调用限流间隔（毫秒） */
    public long getLlmRateLimitMillis() {
        return getLong("llm.rate.limit.ms", 1000);
    }

    /** @return LLM 请求超时（毫秒） */
    public long getLlmTimeoutMillis() {
        return getLong("llm.timeout.ms", 15000);
    }

    /** @return 报价允许偏离最新价的百分比（价格边界保护） */
    public BigDecimal getLlmPriceBandPercent() {
        return getDecimal("llm.price.band.percent", new BigDecimal("5.0"));
    }

    /** @return LLM API Key（来自环境变量）；未配置返回空串 */
    public String getLlmApiKey() {
        return getString("llm.api.key", "");
    }

    /** @return 数据输出目录 */
    public Path getDataDir() {
        return Path.of(getString("io.data.dir", "data"));
    }

    /**
     * 解析初始持仓配置，格式如 {@code AAPL=100,GOOGL=50}。
     *
     * <p>规格只给了 initial.balance，但只有现金、没有任何股票时卖出校验必然失败，
     * 市场无法产生第一笔成交，因此这里补充了 initial.holdings 配置项。</p>
     *
     * @param key 配置键，例如 {@code agent.noise.initial.holdings}
     * @return 标的 → 初始股数（保持配置顺序）；未配置返回空 Map
     */
    public Map<String, Long> getInitialHoldings(String key) {
        String raw = getString(key, "");
        Map<String, Long> holdings = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            return holdings;
        }
        for (String item : raw.split(",")) {
            String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("[=:]");
            if (parts.length != 2) {
                throw new IllegalStateException("初始持仓格式应为 SYMBOL=数量：" + trimmed);
            }
            holdings.put(parts[0].trim(), Long.parseLong(parts[1].trim()));
        }
        return holdings;
    }
}
