package com.kama.jchatmind.agent.tools;

import com.kama.jchatmind.service.HybridSearchService;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class KnowledgeTools implements Tool {

    private final HybridSearchService hybridSearchService;

    public KnowledgeTools(HybridSearchService hybridSearchService) {
        this.hybridSearchService = hybridSearchService;
    }

    @Override
    public String getName() {
        return "KnowledgeTool";
    }

    @Override
    public String getDescription() {
        return "用于从知识库执行语义检索（RAG）。适合回答与文档、资料、项目知识或用户上传内容有关的问题。输入知识库 ID 和查询文本，返回带编号的证据片段。";
    }

    @Override
    public ToolType getType() {
        return ToolType.FIXED;
    }

    @org.springframework.ai.tool.annotation.Tool(
            name = "KnowledgeTool",
            description = "从指定知识库中执行混合检索（BM25 + 向量检索 + RRF/rerank）。当问题涉及文档、资料、上传内容、项目知识或事实依据不足时优先使用。参数为知识库 ID（kbsId）和具体查询文本（query）。返回带编号的证据片段；最终回答必须基于这些片段，不足时说明知识库依据不足。"
    )
    public String knowledgeQuery(String kbsId, String query) {
        List<HybridSearchService.HybridResult> results = hybridSearchService.search(kbsId, query, 5);
        if (results.isEmpty()) {
            return """
                    未检索到相关知识库片段。
                    请不要编造答案；如果无法基于当前知识库回答，请明确说明“当前知识库没有足够依据”。
                    """;
        }

        String evidence = java.util.stream.IntStream.range(0, results.size())
                .mapToObj(i -> {
                    HybridSearchService.HybridResult result = results.get(i);
                    return """
                            [证据片段 %d]
                            chunkId: %s
                            score: %.6f
                            metadata: %s
                            content:
                            %s
                            """.formatted(
                            i + 1,
                            result.chunkId,
                            result.rrfScore,
                            result.metadata == null ? "{}" : result.metadata,
                            result.content
                    );
                })
                .collect(java.util.stream.Collectors.joining("\n"));

        return """
                以下是知识库检索结果。请严格基于这些证据片段回答用户问题。

                使用规则：
                1. 优先使用片段中的事实，不要编造片段外的信息。
                2. 如果片段无法回答问题，请说明“当前知识库没有足够依据”。
                3. 如果片段之间存在冲突，请指出冲突。
                4. 最终回答中可引用证据片段编号，例如“根据证据片段 1”。

                %s
                """.formatted(evidence);
    }
}
