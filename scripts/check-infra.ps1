# ==============================================================================
# MyAgent 基础设施健康检查脚本 (PowerShell)
# 用于快速验证 docker-compose 启动的 5 大中间件是否正常存活并能对外响应
# ==============================================================================

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host "🔍 正在对 MyAgent 基础设施进行可用性检测..." -ForegroundColor Cyan
Write-Host "=================================================" -ForegroundColor Cyan

# 1. 检测 PostgreSQL
try {
    $pgResponse = Test-NetConnection -ComputerName "localhost" -Port 5432 -WarningAction SilentlyContinue
    if ($pgResponse.TcpTestSucceeded) {
        Write-Host "✅ [PostgreSQL + pgvector] 端口 5432 连通正常" -ForegroundColor Green
    } else {
        Write-Host "❌ [PostgreSQL] 端口 5432 无法连接，请确认容器是否启动" -ForegroundColor Red
    }
} catch {
    Write-Host "❌ [PostgreSQL] 检测异常: $_" -ForegroundColor Red
}

# 2. 检测 Qdrant
try {
    $qdrantResponse = Invoke-RestMethod -Uri "http://localhost:6333/readyz" -Method Get -TimeoutSec 3 -ErrorAction SilentlyContinue
    if ($qdrantResponse -match "ready|all good") {
        Write-Host "✅ [Qdrant 向量数据库] HTTP 6333 状态正常 (readyz: $qdrantResponse)" -ForegroundColor Green
        Write-Host "   👉 Web 控制台: http://localhost:6333/dashboard" -ForegroundColor Gray
    } else {
        Write-Host "⚠️ [Qdrant] 端口连通但返回状态: $qdrantResponse" -ForegroundColor Yellow
    }
} catch {
    Write-Host "❌ [Qdrant] HTTP 6333 无法访问，请检查容器状态" -ForegroundColor Red
}

# 3. 检测 Elasticsearch
try {
    $esResponse = Invoke-RestMethod -Uri "http://localhost:9200" -Method Get -TimeoutSec 3 -ErrorAction SilentlyContinue
    if ($esResponse.tagline -match "You Know, for Search") {
        Write-Host "✅ [Elasticsearch 8.x] HTTP 9200 连通正常 (版本: $($esResponse.version.number))" -ForegroundColor Green
    } else {
        Write-Host "⚠️ [Elasticsearch] 返回未匹配到预期标识" -ForegroundColor Yellow
    }
} catch {
    Write-Host "❌ [Elasticsearch] HTTP 9200 无法访问，请检查容器状态" -ForegroundColor Red
}

# 4. 检测 Elasticvue Web 界面
try {
    $evResponse = Invoke-WebRequest -Uri "http://localhost:8088" -Method Get -TimeoutSec 3 -ErrorAction SilentlyContinue
    if ($evResponse.StatusCode -eq 200) {
        Write-Host "✅ [Elasticvue Web 控制台] HTTP 8088 访问正常" -ForegroundColor Green
        Write-Host "   👉 Web 控制台: http://localhost:8088" -ForegroundColor Gray
    }
} catch {
    Write-Host "❌ [Elasticvue] HTTP 8088 无法访问" -ForegroundColor Red
}

# 5. 检测 MinIO 对象存储
try {
    $minioResponse = Invoke-WebRequest -Uri "http://localhost:9100/minio/health/live" -Method Get -TimeoutSec 3 -ErrorAction SilentlyContinue
    if ($minioResponse.StatusCode -eq 200) {
        Write-Host "✅ [MinIO S3 对象存储] 9100 端口存活" -ForegroundColor Green
        Write-Host "   👉 Web 控制台: http://localhost:9101 (账号: minioadmin / 密码: minioadmin_password)" -ForegroundColor Gray
    }
} catch {
    Write-Host "❌ [MinIO] HTTP 9100 无法访问" -ForegroundColor Red
}

Write-Host "=================================================" -ForegroundColor Cyan
Write-Host "💡 提示：如需一键启动所有容器，请在根目录执行: docker compose up -d" -ForegroundColor Yellow
Write-Host "=================================================" -ForegroundColor Cyan
