package com.yupi.yuaiagent.chatmemory;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.objenesis.strategy.StdInstantiatorStrategy;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于MySQL数据库持久化的对话记忆
 */
public class MysqlChatMemory implements ChatMemory {

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private static final Kryo kryo = new Kryo();

    static {
        kryo.setRegistrationRequired(false);
        // 设置实例化策略
        kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
    }

    // 构造对象时，指定数据库连接信息
    public MysqlChatMemory(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
        
        // 初始化数据库表
        initTable();
    }

    /**
     * 初始化数据库表
     */
    private void initTable() {
        try (Connection conn = getConnection()) {
            String createTableSQL = 
                "CREATE TABLE IF NOT EXISTS chat_memory (" +
                "conversation_id VARCHAR(255) PRIMARY KEY, " +
                "messages LONGBLOB NOT NULL, " +
                "update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP" +
                ")";
            
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createTableSQL);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to initialize chat_memory table", e);
        }
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        List<Message> conversationMessages = getOrCreateConversation(conversationId);
        conversationMessages.addAll(messages);
        saveConversation(conversationId, conversationMessages);
    }

    @Override
    public List<Message> get(String conversationId) {
        return getOrCreateConversation(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        try (Connection conn = getConnection()) {
            String sql = "DELETE FROM chat_memory WHERE session_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, conversationId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to clear conversation: " + conversationId, e);
        }
    }

    private List<Message> getOrCreateConversation(String conversationId) {
        try (Connection conn = getConnection()) {
            String sql = "SELECT messages FROM chat_memory WHERE session_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, conversationId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        byte[] data = rs.getBytes("messages");
                        return deserializeMessages(data);
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to get conversation: " + conversationId, e);
        }
        
        // 如果不存在，返回空列表
        return new ArrayList<>();
    }

    private void saveConversation(String conversationId, List<Message> messages) {
        byte[] data = serializeMessages(messages);
        
        try (Connection conn = getConnection()) {
            String sql = "REPLACE INTO chat_memory (session_id, messages) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, conversationId);
                ps.setBytes(2, data);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to save conversation: " + conversationId, e);
        }
    }

    private Connection getConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    /**
     * 使用Kryo序列化消息列表
     */
    private byte[] serializeMessages(List<Message> messages) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             Output output = new Output(baos)) {
            kryo.writeObject(output, messages);
            output.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize messages", e);
        }
    }

    /**
     * 使用Kryo反序列化消息列表
     */
    @SuppressWarnings("unchecked")
    private List<Message> deserializeMessages(byte[] data) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             Input input = new Input(bais)) {
            return kryo.readObject(input, ArrayList.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize messages", e);
        }
    }
} 