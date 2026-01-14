package com.yupi.yuaiagent.rag.hybrid;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.yupi.yuaiagent.rag.QueryRewriter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 简单混合降级检索服务
 * 优先使用PgVector，不行时降级到MySQL
 */
@Service
@Slf4j
public class SimpleHybridSearchService {

    @Resource
    private VectorStore pgVectorVectorStore;

    @Resource
    private VectorStore loveAppVectorStore;

    @Resource
    private EmbeddingModel dashscopeembeddingModel;

    @Resource
    private ChatModel dashscopeChatModel;
    
    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;
    
    @Resource
    private QueryRewriter queryRewriter;
    
    // 查询缓存，存储热门查询的结果
    private final Map<String, HybridSearchResult> queryCache = new ConcurrentHashMap<>();
    
    // 缓存过期时间（毫秒）
    private static final long CACHE_EXPIRY = 1000 * 60 * 30; // 30分钟
    
    // 缓存时间戳
    private final Map<String, Long> cacheTimestamps = new ConcurrentHashMap<>();
    
    // 相关性评分阈值，低于此值触发降级
    private static final double RELEVANCE_THRESHOLD = 0.6;

    /**
     * 混合检索结果包装类
     * 包含检索到的文档和相关元数据
     */
    public static class HybridSearchResult {
        private final List<Document> documents;
        private final String summary;
        private final double relevanceScore;
        private final String searchStrategy; // 记录使用的检索策略

        public HybridSearchResult(List<Document> documents, String summary, double relevanceScore, String searchStrategy) {
            this.documents = documents;
            this.summary = summary;
            this.relevanceScore = relevanceScore;
            this.searchStrategy = searchStrategy;
        }

        public List<Document> getDocuments() {
            return documents;
        }

        public String getSummary() {
            return summary;
        }
        
        public double getRelevanceScore() {
            return relevanceScore;
        }
        
        public String getSearchStrategy() {
            return searchStrategy;
        }
    }

    /**
     * 执行混合降级检索
     *
     * @param query 查询文本
     * @param status 状态过滤（可选）
     * @param limit 返回结果数量限制
     * @return 混合检索结果
     */
    @Cacheable(value = "searchResults", key = "#query + #status + #limit", unless = "#result.documents.isEmpty()")
    public HybridSearchResult search(String query, String status, int limit) {
        log.info("执行混合降级检索，查询：{}，状态：{}，限制：{}", query, status, limit);
        
        // 尝试从缓存获取结果
        String cacheKey = generateCacheKey(query, status, limit);
        if (queryCache.containsKey(cacheKey)) {
            Long timestamp = cacheTimestamps.get(cacheKey);
            if (timestamp != null && System.currentTimeMillis() - timestamp < CACHE_EXPIRY) {
                log.info("从缓存获取结果");
                return queryCache.get(cacheKey);
            } else {
                // 缓存过期，移除
                queryCache.remove(cacheKey);
                cacheTimestamps.remove(cacheKey);
            }
        }
        
        // 查询理解：使用QueryRewriter进行查询重写
        String enhancedQuery = queryRewriter.doQueryRewrite(query);
        log.info("查询重写后的增强查询：{}", enhancedQuery);
        
        List<Document> results = new ArrayList<>();
        double relevanceScore = 0.0;
        String usedStrategy = "";
        
        // 第一步：尝试从PgVector检索
        try {
            results = searchFromPgVector(enhancedQuery, status, limit);
            log.info("PgVector检索结果数量: {}", results.size());
            
            // 评估检索结果质量
            relevanceScore = evaluateSearchResults(results, enhancedQuery);
            log.info("PgVector检索结果相关性评分: {}", relevanceScore);
            
            // 如果结果质量不佳，触发降级
            if (relevanceScore < RELEVANCE_THRESHOLD) {
                log.info("PgVector检索结果相关性低于阈值{}，触发降级", RELEVANCE_THRESHOLD);
                results.clear(); // 清空低质量结果
            } else {
                usedStrategy = "PgVector";
            }
        } catch (Exception e) {
            log.error("PgVector检索失败，降级到内存向量库", e);
        }
        
        // 第二步：如果PgVector检索失败或结果不足，降级到内存向量库
        if (results.isEmpty() || results.size() < limit) {
            try {
                List<Document> vectorResults = searchFromVectorStore(enhancedQuery, status, limit - results.size());
                log.info("内存向量库检索结果数量: {}", vectorResults.size());
                
                // 评估内存向量库检索结果质量
                double vectorRelevanceScore = evaluateSearchResults(vectorResults, enhancedQuery);
                log.info("内存向量库检索结果相关性评分: {}", vectorRelevanceScore);
                
                if (vectorRelevanceScore >= RELEVANCE_THRESHOLD) {
                    results.addAll(vectorResults);
                    // 更新整体相关性评分（加权平均）
                    if (!results.isEmpty()) {
                        relevanceScore = (relevanceScore * (results.size() - vectorResults.size()) 
                                + vectorRelevanceScore * vectorResults.size()) / results.size();
                    } else {
                        relevanceScore = vectorRelevanceScore;
                    }
                    usedStrategy = usedStrategy.isEmpty() ? "VectorStore" : usedStrategy + "+VectorStore";
                } else {
                    log.info("内存向量库检索结果相关性低于阈值{}，触发进一步降级", RELEVANCE_THRESHOLD);
                }
            } catch (Exception e) {
                log.error("内存向量库检索失败，降级到MySQL", e);
            }
        }
        
        // 第三步：如果向量检索结果仍不足，降级到MySQL关键词搜索
        if (results.size() < limit) {
            try {
                List<Document> mysqlResults = searchFromMysql(enhancedQuery, status, limit - results.size());
                log.info("MySQL检索结果数量: {}", mysqlResults.size());
                results.addAll(mysqlResults);
                usedStrategy = usedStrategy.isEmpty() ? "MySQL" : usedStrategy + "+MySQL";
                
                // MySQL结果通常不计入相关性评分，因为它是最后的降级选项
            } catch (Exception e) {
                log.error("MySQL检索失败", e);
            }
        }
        
        // 去重
        results = results.stream()
                .distinct()
                .limit(limit)
                .collect(Collectors.toList());
        
        // 生成摘要
        String summary = "";
        if (!results.isEmpty()) {
            summary = generateSummary(query, results);
        }
        
        // 创建搜索结果
        HybridSearchResult searchResult = new HybridSearchResult(results, summary, relevanceScore, usedStrategy);
        
        // 缓存结果
        queryCache.put(cacheKey, searchResult);
        cacheTimestamps.put(cacheKey, System.currentTimeMillis());
        
        return searchResult;
    }
    
    /**
     * 生成缓存键
     */
    private String generateCacheKey(String query, String status, int limit) {
        return query + "|" + (status != null ? status : "null") + "|" + limit;
    }
    
    /**
     * 评估检索结果质量
     * 返回0-1之间的相关性评分
     */
    private double evaluateSearchResults(List<Document> results, String query) {
        if (results.isEmpty()) {
            return 0.0;
        }

        try {
            // 1) 优先使用检索源自带的分数（相似度/距离）
            double metaScoreSum = 0.0;
            int metaScoreCount = 0;
            for (Document doc : results) {
                Map<String, Object> md = doc.getMetadata();
                if (md == null) {
                    continue;
                }
                // 若有“距离”，转成相似度（简化：sim ≈ 1 - distance，裁剪到[0,1]）
                Object distance = md.get("distance");
                if (distance instanceof Number) {
                    double d = ((Number) distance).doubleValue();
                    double sim = Math.max(0.0, Math.min(1.0, 1.0 - d));
                    metaScoreSum += sim;
                    metaScoreCount++;
                    continue;
                }
                // 若直接有“score”（相似度），直接使用（如需可自行归一化到[0,1]）
                Object score = md.get("score");
                if (score instanceof Number) {
                    double s = ((Number) score).doubleValue();
                    double sim = Math.max(0.0, Math.min(1.0, s));
                    metaScoreSum += sim;
                    metaScoreCount++;
                }
            }
            if (metaScoreCount > 0) {
                return metaScoreSum / metaScoreCount;
            }

            // 2) 无原生分数时，启发式评分（中文用双字词，其他用空白分词）
            boolean chinese = containsChinese(query);
            List<String> queryTokens = chinese
                    ? toChineseNGrams(query, 2)
                    : splitByWhitespaceLower(query);

            if (queryTokens.isEmpty()) {
                return 0.0;
            }

            double totalScore = 0.0;
            for (Document doc : results) {
                String content = doc.getText() == null ? "" : doc.getText();
                List<String> docTokens = chinese
                        ? toChineseNGrams(content, 2)
                        : splitByWhitespaceLower(content);

                java.util.HashSet<String> docSet = new java.util.HashSet<>(docTokens);
                int matchCount = 0;
                for (String tk : queryTokens) {
                    if (docSet.contains(tk)) {
                        matchCount++;
                    }
                }
                double docScore = (double) matchCount / (double) queryTokens.size();
                totalScore += docScore;
            }
            // 返回平均评分
            return totalScore / results.size();
        } catch (Exception e) {
            log.error("评估检索结果质量失败", e);
            return 0.5; // 默认中等相关性
        }
    }

    // 中文检测：字符串中是否包含汉字
    private boolean containsChinese(String s) {
        if (s == null || s.isEmpty()) return false;
        for (char c : s.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    // 将文本转换为中文 n-gram（保留汉字，滑窗切分；长度不足n则退化为逐字）
    private List<String> toChineseNGrams(String text, int n) {
        if (text == null || text.isEmpty()) return java.util.Collections.emptyList();
        StringBuilder onlyHan = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                // 中文不区分大小写，这里保持原样或统一到小写都可
                onlyHan.append(c);
            }
        }
        String s = onlyHan.toString();
        if (s.isEmpty()) return java.util.Collections.emptyList();

        if (s.length() < n) {
            List<String> chars = new java.util.ArrayList<>(s.length());
            for (int i = 0; i < s.length(); i++) {
                chars.add(String.valueOf(s.charAt(i)));
            }
            return chars;
        }

        List<String> ngrams = new java.util.ArrayList<>(s.length() - n + 1);
        for (int i = 0; i <= s.length() - n; i++) {
            ngrams.add(s.substring(i, i + n));
        }
        return ngrams;
    }

    // 英文/带空格文本的分词（小写 + 去空白）
    private List<String> splitByWhitespaceLower(String text) {
        if (text == null || text.isEmpty()) return java.util.Collections.emptyList();
        String[] arr = text.toLowerCase().split("\\s+");
        List<String> list = new java.util.ArrayList<>(arr.length);
        for (String a : arr) {
            if (a != null && a.trim().length() > 0) {
                list.add(a);
            }
        }
        return list;
    }
    
    /**
     * 从PgVector检索
     */
    private List<Document> searchFromPgVector(String query, String status, int limit) {
        try {
            SearchRequest.Builder requestBuilder = SearchRequest.builder()
                    .query(query)
                    .topK(limit);
            
            if (status != null && !status.isEmpty()) {
                // 添加过滤条件
                requestBuilder.filterExpression("status = '" + status + "'");
            }
            
            return pgVectorVectorStore.similaritySearch(requestBuilder.build());
        } catch (Exception e) {
            log.error("PgVector检索失败", e);
            throw e;
        }
    }
    
    /**
     * 从内存向量库检索
     */
    private List<Document> searchFromVectorStore(String query, String status, int limit) {
        SearchRequest.Builder requestBuilder = SearchRequest.builder()
                .query(query)
                .topK(limit * 2);
        
        List<Document> results = loveAppVectorStore.similaritySearch(requestBuilder.build());
        
        // 如果需要按状态过滤
        if (status != null && !status.isEmpty()) {
            results = results.stream()
                    .filter(doc -> {
                        Map<String, Object> metadata = doc.getMetadata();
                        return metadata != null && 
                               status.equals(metadata.getOrDefault("status", "").toString());
                    })
                    .limit(limit)
                    .collect(Collectors.toList());
        }
        
        return results;
    }
    
    /**
     * 从MySQL检索
     */
    private List<Document> searchFromMysql(String query, String status, int limit) {
        String sql;
        List<Map<String, Object>> rows;
        
        if (status != null && !status.isEmpty()) {
            sql = "SELECT * FROM documents WHERE MATCH(content) AGAINST(? IN NATURAL LANGUAGE MODE) AND status = ? LIMIT ?";
            rows = jdbcTemplate.queryForList(sql, query, status, limit);
        } else {
            sql = "SELECT * FROM documents WHERE MATCH(content) AGAINST(? IN NATURAL LANGUAGE MODE) LIMIT ?";
            rows = jdbcTemplate.queryForList(sql, query, limit);
        }
        
        return rows.stream().map(row -> {
            String id = row.get("id").toString();
            String content = row.get("content").toString();
            Map<String, Object> metadata = new HashMap<>();
            
            row.forEach((key, value) -> {
                if (!key.equals("id") && !key.equals("content") && value != null) {
                    metadata.put(key, value.toString());
                }
            });
            
            Document doc = new Document(content, metadata);
            // 手动设置id
            doc.getMetadata().put("id", id);
            return doc;
        }).collect(Collectors.toList());
    }
    
    /**
     * 生成结果摘要
     */
    private String generateSummary(String query, List<Document> documents) {
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("请根据以下文档内容，为用户的问题生成一个简洁的摘要：\n\n");
        promptBuilder.append("用户问题: ").append(query).append("\n\n");
        
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            promptBuilder.append("文档 ").append(i + 1).append(":\n");
            promptBuilder.append(doc.getText()).append("\n\n");
        }
        
        promptBuilder.append("请生成一个200字以内的摘要，概括这些文档对用户问题的回答要点。");
        
        return dashscopeChatModel.call(new Prompt(promptBuilder.toString()))
                .getResult()
                .getOutput()
                .getText();
    }
    
    /**
     * 清除缓存
     */
    public void clearCache() {
        queryCache.clear();
        cacheTimestamps.clear();
        log.info("查询缓存已清除");
    }
}