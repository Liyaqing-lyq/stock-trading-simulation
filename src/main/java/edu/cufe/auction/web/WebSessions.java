package edu.cufe.auction.web;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端会话（仅内存）：管理员会话与绑定人物的个人会话。
 *
 * <p>只由 {@link WorldWebServer} 使用；不含任何持久化与公开登录入口。</p>
 */
final class WebSessions {

    /** 会话角色。 */
    enum Role {
        ADMIN,
        PERSON
    }

    /**
     * 已认证会话。
     *
     * @param role      角色
     * @param personId  个人会话绑定的人物；管理员为 null
     */
    record Session(Role role, String personId) {
    }

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /** 生成 256bit 随机 token 并登记会话。 */
    String create(Role role, String personId) {
        byte[] material = new byte[32];
        random.nextBytes(material);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(material);
        sessions.put(token, new Session(role, personId));
        return token;
    }

    Optional<Session> resolve(String token) {
        return token == null || token.isEmpty() ? Optional.empty()
                : Optional.ofNullable(sessions.get(token));
    }

    void invalidate(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** 清空全部会话（管理员重新登录时使旧人物/旧管理员会话全部失效）。 */
    void clear() {
        sessions.clear();
    }

    int size() {
        return sessions.size();
    }
}
