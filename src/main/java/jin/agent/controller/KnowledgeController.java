package jin.agent.controller;

import jin.agent.service.KnowledgeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 知识库管理端点：提供文档工业级摄取流水线、文档清单调试、语义搜索与 RAG 问答
 */
@RestController
@RequestMapping("/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /**
     * 工业级文档摄取/更新端点
     * 传参：
     * - file: 上传的文件（支持 txt、markdown 等各类文本文件）
     * - documentId: 调用方显式声明的业务唯一标识（如 "onboarding-guide"）
     */
    @PostMapping("/documents")
    public ResponseEntity<KnowledgeService.IngestResult> ingestDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("documentId") String documentId) {
        KnowledgeService.IngestResult result = knowledgeService.ingestDocument(file, documentId);
        return ResponseEntity.ok(result);
    }

    /**
     * 查询 MinIO 中已有的所有 documentId 列表（方便调试与排查）
     */
    @GetMapping("/documents")
    public ResponseEntity<List<String>> listDocumentIds() {
        List<String> docIds = knowledgeService.listDocumentIds();
        return ResponseEntity.ok(docIds);
    }

    /**
     * 语义向量检索端点
     */
    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "3") int maxResults,
            @RequestParam(defaultValue = "0.5") double minScore) {
        long startTime = System.currentTimeMillis();
        List<KnowledgeService.SearchResultItem> matches = knowledgeService.search(query, maxResults, minScore);
        long cost = System.currentTimeMillis() - startTime;

        return ResponseEntity.ok(Map.of(
                "query", query,
                "count", matches.size(),
                "matches", matches,
                "costMs", cost,
                "status", "success"
        ));
    }

    /**
     * 完整 RAG 检索增强生成问答端点
     */
    @GetMapping("/ask")
    public ResponseEntity<Map<String, Object>> ask(
            @RequestParam String query,
            @RequestParam(defaultValue = "2") int maxResults,
            @RequestParam(defaultValue = "0.5") double minScore) {
        Map<String, Object> result = knowledgeService.ask(query, maxResults, minScore);
        return ResponseEntity.ok(result);
    }
}
