package org.es.tok;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * Performance tests for ES-TOK analyzer using the new test framework
 */
public class PerformanceTest {

    public static void main(String[] args) throws IOException {
        System.out.println("=== ES-TOK Performance Tests ===\n");

        // Test data
        String shortText = "深度学习机器学习人工智能";
        String longText = "深度学习是机器学习的一个分支，它模仿人脑神经网络的工作方式。" +
                "通过多层神经网络，深度学习能够自动学习数据的特征表示，" +
                "在图像识别、自然语言处理、语音识别等领域取得了突破性进展。" +
                "目前，深度学习已广泛应用于各行各业，推动了人工智能技术的快速发展。";

        List<String> vocabs = Arrays.asList(
                "深度学习", "机器学习", "人工智能", "神经网络", "自然语言", "语音识别",
                "图像识别", "特征", "数据", "技术", "发展", "应用", "领域");

        // Performance test 1: Basic configuration
        performanceTest("Basic Vocab + Categ", shortText, 1000,
                ConfigBuilder.create()
                        .withVocab(vocabs)
                        .withCateg()
                        .build());

        // Performance test 2: With N-grams
        performanceTest("Vocab + Categ + All N-grams", shortText, 1000,
                ConfigBuilder.create()
                        .withVocab(vocabs)
                        .withCategSplitWord()
                        .withAllNgrams()
                        .withDropDuplicates()
                        .build());

        // Performance test 3: Long text
        performanceTest("Long Text Processing", longText, 100,
                ConfigBuilder.create()
                        .withVocab(vocabs)
                        .withCategSplitWord()
                        .withAllNgrams()
                        .withIgnoreCase()
                        .withDropDuplicates()
                        .build());

        // Performance test 4: Heavy vocabulary
        performanceTest("Heavy Vocabulary", shortText, 500,
                ConfigBuilder.create()
                        .withVocab(generateHeavyVocabs())
                        .withCateg()
                        .withIgnoreCase()
                        .build());
    }

    private static void performanceTest(String testName, String text, int iterations,
            org.es.tok.config.EsTokConfig config) throws IOException {
        System.out.println("=== Performance Test: " + testName + " ===");
        System.out.println("Text length: " + text.length() + " characters");
        System.out.println("Iterations: " + iterations);
        System.out.println("Config: " + config);

        // Warmup
        for (int i = 0; i < 10; i++) {
            TestUtils.testQuietly(text, config);
        }

        // Actual timing
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            TestUtils.testQuietly(text, config);
        }
        long endTime = System.nanoTime();

        double totalTimeMs = (endTime - startTime) / 1_000_000.0;
        double avgTimeMs = totalTimeMs / iterations;
        double throughput = iterations / (totalTimeMs / 1000.0);

        System.out.printf("Total time: %.2f ms%n", totalTimeMs);
        System.out.printf("Average time per iteration: %.3f ms%n", avgTimeMs);
        System.out.printf("Throughput: %.2f ops/sec%n", throughput);
        System.out.println();
    }

    private static List<String> generateHeavyVocabs() {
        return Arrays.asList(
                "深度", "学习", "机器", "人工", "智能", "神经", "网络", "算法", "模型", "训练",
                "数据", "特征", "分类", "回归", "聚类", "优化", "梯度", "反向", "传播", "卷积",
                "循环", "注意", "机制", "变换", "编码", "解码", "生成", "判别", "监督", "无监督",
                "强化", "迁移", "微调", "预训练", "大模型", "语言", "视觉", "语音", "多模态", "推理",
                "知识", "图谱", "表示", "嵌入", "向量", "相似", "距离", "聚合", "池化", "激活",
                "正则", "批归", "丢弃", "残差", "跳跃", "连接", "融合", "集成", "蒸馏", "压缩");
    }
}
