package edu.cufe.auction.gui;

import javafx.application.Application;

/**
 * 启动包装类：让界面能在 IDE 里直接右键 Run，也能用普通 classpath 方式启动。
 *
 * <p><b>为什么需要这个类</b>：如果直接运行 {@link AuctionApp}（它继承
 * {@link Application}），JavaFX 的启动器会要求把 JavaFX 放在 <b>module-path</b> 上，
 * 否则报 <code>Error: JavaFX runtime components are missing</code>——而 Maven 依赖方式
 * 是把 JavaFX 放在 classpath 上。用一个「不继承 Application」的类做入口即可绕开该检查。</p>
 *
 * <p>两种启动方式都可用：</p>
 * <ul>
 *   <li>IDE 里直接 Run 本类（用 classpath）；</li>
 *   <li>命令行 {@code mvnw.cmd javafx:run}（用 module-path，配置见 pom.xml）。</li>
 * </ul>
 */
public final class Launcher {

    private Launcher() {
    }

    /**
     * 主入口。
     *
     * @param args 透传给 JavaFX 的参数
     */
    public static void main(String[] args) {
        Application.launch(AuctionApp.class, args);
    }
}
