package com.sunlee.bus.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class MemberPaymentPinService {
    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private JdbcTemplate jdbc;

    private byte[] derive(String pin, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("支付密码处理失败", e);
        } finally {
            spec.clearPassword();
        }
    }

    @Transactional
    public void setPin(Integer memberId, String pin) {
        if (memberId == null || pin == null || !pin.matches("\\d{6}")) {
            throw new IllegalArgumentException("支付密码必须是6位数字");
        }
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        String hash = "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt)
                + "$" + Base64.getEncoder().encodeToString(derive(pin, salt, ITERATIONS));
        jdbc.update("INSERT INTO bus_member_payment_pin (member_id, pin_hash) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE pin_hash = VALUES(pin_hash), failed_attempts = 0, locked_until = NULL",
                memberId, hash);
    }

    /** 返回 null 表示校验成功；错误次数在调用收银事务之前持久化。 */
    @Transactional
    public String verify(Integer memberId, String pin) {
        if (memberId == null || pin == null || !pin.matches("\\d{6}")) {
            return "请输入6位数字支付密码";
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT pin_hash, failed_attempts, locked_until > NOW() AS is_locked "
                        + "FROM bus_member_payment_pin WHERE member_id = ? FOR UPDATE", memberId);
        if (rows.isEmpty()) {
            return "该会员尚未设置支付密码，请先到会员管理中设置";
        }
        Map<String, Object> row = rows.get(0);
        Object locked = row.get("is_locked");
        if (Boolean.TRUE.equals(locked) || (locked instanceof Number && ((Number) locked).intValue() != 0)) {
            return "支付密码已锁定，请15分钟后重试或联系管理员重置";
        }
        String[] parts = ((String) row.get("pin_hash")).split("\\$");
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        if (MessageDigest.isEqual(expected, derive(pin, salt, Integer.parseInt(parts[1])))) {
            jdbc.update("UPDATE bus_member_payment_pin SET failed_attempts = 0, locked_until = NULL WHERE member_id = ?", memberId);
            return null;
        }
        int failures = ((Number) row.get("failed_attempts")).intValue() + 1;
        if (failures >= 5) {
            jdbc.update("UPDATE bus_member_payment_pin SET failed_attempts = 0, "
                    + "locked_until = DATE_ADD(NOW(), INTERVAL 15 MINUTE) WHERE member_id = ?", memberId);
            return "支付密码错误次数过多，已锁定15分钟";
        }
        jdbc.update("UPDATE bus_member_payment_pin SET failed_attempts = ? WHERE member_id = ?", failures, memberId);
        return "支付密码错误，剩余" + (5 - failures) + "次机会";
    }
}
