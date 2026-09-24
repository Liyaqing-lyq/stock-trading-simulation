package edu.cufe.auction.gui;

import javafx.application.Application;

/**
 * 应用入口（图形界面）。IDE 里直接右键 Run 本类，或命令行 {@code mvnw.cmd javafx:run}。
 *
 * <p><b>为什么入口类不继承 {@link Application}</b>：JavaFX 的启动器要求「主类继承 Application 时，
 * JavaFX 必须位于 module-path」。而 Maven 依赖方式把 JavaFX 放在 classpath，于是直接运行继承
 * Application 的类会报 <code>错误: 缺少 JavaFX 运行时组件</code>——实测即使该类没有 main 方法也照样报。
 * 因此这里用「不继承 Application 的入口类 + 由它调用 {@code Application.launch}」的写法，
 * 界面实现放在 {@link AuctionUi}。</p>
 *
 * <p>三种启动方式等价，任选其一：</p>
 * <ol>
 *   <li>IDE：右键本类 → Run（走 classpath）；</li>
 *   <li>命令行：{@code mvnw.cmd javafx:run}（走 module-path）；</li>
 *   <li>打好的 jar：{@code java -cp <类路径> edu.cufe.auction.gui.AuctionApp}。</li>
 * </ol>
 */
public final class AuctionApp {

    private AuctionApp() {
    }

    /**
     * 主入口。
     *
     * @param args 透传给 JavaFX 的参数
     */
    public static void main(String[] args) {
        Application.launch(AuctionUi.class, args);
    }
}
