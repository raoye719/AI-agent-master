package com.yupi.yuaiagent.chatmemory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MysqlChatMemoryTest {

    private static final String JDBC_URL = "jdbc:mysql://localhost:3306/chatmemory?serverTimezone=Asia/Shanghai";
    private static final String USERNAME = "root";
    private static final String PASSWORD = "123456";
    private static final String TEST_CONVERSATION_ID = "test-conversation";

    private MysqlChatMemory mysqlChatMemory;

    @BeforeEach
    void setUp() {
        // 初始化 MysqlChatMemory 对象
        mysqlChatMemory = new MysqlChatMemory(JDBC_URL, USERNAME, PASSWORD);
        
        // 清理测试数据
        cleanUpTestData();
    }

//    @AfterEach
//    void tearDown() {
//        // 清理测试数据
//        cleanUpTestData();
//    }

    @Test
    void testAddAndGetMessages() {
        // 创建测试消息
        UserMessage userMessage = new UserMessage("Hello, AI!");
        AssistantMessage assistantMessage = new AssistantMessage("Hello, User!");
        List<Message> messages = Arrays.asList(userMessage, assistantMessage);

        // 添加消息
        mysqlChatMemory.add(TEST_CONVERSATION_ID, messages);

        // 获取消息
        List<Message> retrievedMessages = mysqlChatMemory.get(TEST_CONVERSATION_ID);

        // 验证
        assertNotNull(retrievedMessages);
        assertEquals(2, retrievedMessages.size());
        assertEquals("Hello, AI!", ((UserMessage) retrievedMessages.get(0)).getText());
        assertEquals("Hello, User!", ((AssistantMessage) retrievedMessages.get(1)).getText());
    }

    @Test
    void testClearMessages() {
        // 创建测试消息
        UserMessage userMessage = new UserMessage("Hello, AI!");
        List<Message> messages = Arrays.asList(userMessage);

        // 添加消息
        mysqlChatMemory.add(TEST_CONVERSATION_ID, messages);

        // 清除消息
        mysqlChatMemory.clear(TEST_CONVERSATION_ID);

        // 获取消息
        List<Message> retrievedMessages = mysqlChatMemory.get(TEST_CONVERSATION_ID);

        // 验证
        assertNotNull(retrievedMessages);
        assertEquals(0, retrievedMessages.size());
    }

    @Test
    void testAddMultipleMessages() {
        // 创建第一批测试消息
        UserMessage userMessage1 = new UserMessage("First message");
        List<Message> messages1 = Arrays.asList(userMessage1);

        // 添加第一批消息
        mysqlChatMemory.add(TEST_CONVERSATION_ID, messages1);

        // 创建第二批测试消息
        AssistantMessage assistantMessage = new AssistantMessage("Response to first");
        UserMessage userMessage2 = new UserMessage("Second message");
        List<Message> messages2 = Arrays.asList(assistantMessage, userMessage2);

        // 添加第二批消息
        mysqlChatMemory.add(TEST_CONVERSATION_ID, messages2);

        // 获取消息
        List<Message> retrievedMessages = mysqlChatMemory.get(TEST_CONVERSATION_ID);
        retrievedMessages.stream().forEach(System.out::println);

        // 验证
        assertNotNull(retrievedMessages);
        assertEquals(3, retrievedMessages.size());
        assertEquals("First message", ((UserMessage) retrievedMessages.get(0)).getText());
        assertEquals("Response to first", ((AssistantMessage) retrievedMessages.get(1)).getText());
        assertEquals("Second message", ((UserMessage) retrievedMessages.get(2)).getText());
    }

    private void cleanUpTestData() {
        try (Connection conn = DriverManager.getConnection(JDBC_URL, USERNAME, PASSWORD)) {
            String sql = "DELETE FROM chat_memory WHERE session_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, TEST_CONVERSATION_ID);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            // 忽略异常，可能表不存在
        }
    }
} 