import Foundation
import Vision
import AppKit
import Network

struct BridgeConfig {
    let port: UInt16
    let bridgeKey: String
    let translateURL: URL
    let tokenCountURL: URL
    let compactURL: URL
    let apiKey: String
    let model: String
    let compactModel: String
    let temperature: Double
    let retryTemperature: Double
    let compactTemperature: Double
    let topP: Double
    let maxTokens: Int
    let compactMaxTokens: Int
    let ocrLanguages: [String]
    let useLanguageCorrection: Bool
    let maxImageBytes: Int
    let stripThinkTags: Bool
    let contextSize: Int
    let outputReserveTokens: Int
    let contextSafetyTokens: Int
    let compactTriggerRatio: Double
    let compactHardRatio: Double
    let compactTargetTokens: Int
    let recentHistoryEntries: Int
    let hardRecentHistoryEntries: Int
    let sessionDirectory: URL
    let sessionPersist: Bool
    let cachePrompt: Bool
    let promptVersion: String

    static func load() throws -> BridgeConfig {
        let env = ProcessInfo.processInfo.environment

        func string(_ key: String, _ fallback: String) -> String {
            let value = env[key]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return value.isEmpty ? fallback : value
        }

        func bool(_ key: String, _ fallback: Bool) -> Bool {
            let value = string(key, fallback ? "true" : "false").lowercased()
            return ["1", "true", "yes", "on"].contains(value)
        }

        func boundedDouble(_ key: String, _ fallback: Double, _ low: Double, _ high: Double) -> Double {
            let value = Double(string(key, String(fallback))) ?? fallback
            return max(low, min(high, value))
        }

        let port = UInt16(string("BRIDGE_PORT", "8090")) ?? 8090
        let bridgeKey = string("BRIDGE_KEY", "")
        let translateURLString = string("TRANSLATE_URL", "http://127.0.0.1:8080/v1/chat/completions")
        guard let translateURL = URL(string: translateURLString) else {
            throw BridgeError.config("TRANSLATE_URL 无效: \(translateURLString)")
        }

        let tokenURLString = string(
            "LLAMA_TOKEN_COUNT_URL",
            siblingURLString(from: translateURL, path: "/v1/chat/completions/input_tokens")
        )
        guard let tokenCountURL = URL(string: tokenURLString) else {
            throw BridgeError.config("LLAMA_TOKEN_COUNT_URL 无效: \(tokenURLString)")
        }

        let compactURLString = string("COMPACT_URL", translateURL.absoluteString)
        guard let compactURL = URL(string: compactURLString) else {
            throw BridgeError.config("COMPACT_URL 无效: \(compactURLString)")
        }

        let languages = string("OCR_LANGUAGES", "ja-JP")
            .split(separator: ",")
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }

        let defaultSessionDir = FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Application Support/SakuraVisionBridge/sessions", isDirectory: true)
            .path
        let sessionPath = NSString(string: string("SESSION_DIR", defaultSessionDir)).expandingTildeInPath

        return BridgeConfig(
            port: port,
            bridgeKey: bridgeKey,
            translateURL: translateURL,
            tokenCountURL: tokenCountURL,
            compactURL: compactURL,
            apiKey: string("TRANSLATE_API_KEY", ""),
            model: string("TRANSLATE_MODEL", "Sakura-GalTransl-7B-v3.7"),
            compactModel: string("COMPACT_MODEL", string("TRANSLATE_MODEL", "Sakura-GalTransl-7B-v3.7")),
            temperature: Double(string("TEMPERATURE", "0.3")) ?? 0.3,
            retryTemperature: Double(string("RETRY_TEMPERATURE", "0.2")) ?? 0.2,
            compactTemperature: Double(string("COMPACT_TEMPERATURE", "0.1")) ?? 0.1,
            topP: Double(string("TOP_P", "0.8")) ?? 0.8,
            maxTokens: max(128, min(2048, Int(string("MAX_TOKENS", "512")) ?? 512)),
            compactMaxTokens: max(128, min(2048, Int(string("COMPACT_MAX_TOKENS", "800")) ?? 800)),
            ocrLanguages: languages.isEmpty ? ["ja-JP"] : languages,
            useLanguageCorrection: bool("OCR_LANGUAGE_CORRECTION", false),
            maxImageBytes: Int(string("MAX_IMAGE_BYTES", "12582912")) ?? 12_582_912,
            stripThinkTags: bool("STRIP_THINK_TAGS", true),
            contextSize: max(1024, Int(string("LLAMA_CTX_SIZE", "8192")) ?? 8192),
            outputReserveTokens: max(128, Int(string("OUTPUT_RESERVE_TOKENS", "512")) ?? 512),
            contextSafetyTokens: max(128, Int(string("CONTEXT_SAFETY_TOKENS", "512")) ?? 512),
            compactTriggerRatio: boundedDouble("COMPACT_TRIGGER_RATIO", 0.72, 0.30, 0.90),
            compactHardRatio: boundedDouble("COMPACT_HARD_RATIO", 0.85, 0.50, 0.98),
            compactTargetTokens: max(512, Int(string("COMPACT_TARGET_TOKENS", "2500")) ?? 2500),
            recentHistoryEntries: max(2, min(40, Int(string("RECENT_HISTORY_ENTRIES", "12")) ?? 12)),
            hardRecentHistoryEntries: max(1, min(12, Int(string("HARD_RECENT_HISTORY_ENTRIES", "4")) ?? 4)),
            sessionDirectory: URL(fileURLWithPath: sessionPath, isDirectory: true),
            sessionPersist: bool("SESSION_PERSIST", true),
            cachePrompt: bool("LLAMA_CACHE_PROMPT", true),
            promptVersion: string("PROMPT_VERSION", "sakura-v3.7-mw-phase-b-session-001")
        )
    }

    private static func siblingURLString(from base: URL, path: String) -> String {
        var components = URLComponents(url: base, resolvingAgainstBaseURL: false)
        components?.path = path
        components?.query = nil
        components?.fragment = nil
        return components?.url?.absoluteString ?? base.absoluteString
    }

    var usableContextTokens: Int {
        max(256, contextSize - outputReserveTokens - contextSafetyTokens)
    }

    var compactTriggerTokens: Int {
        Int(Double(usableContextTokens) * compactTriggerRatio)
    }

    var compactHardTokens: Int {
        Int(Double(usableContextTokens) * compactHardRatio)
    }
}

enum BridgeError: Error, CustomStringConvertible {
    case config(String)
    case badRequest(String)
    case ocr(String)
    case upstream(String)

    var description: String {
        switch self {
        case .config(let message): return message
        case .badRequest(let message): return message
        case .ocr(let message): return message
        case .upstream(let message): return message
        }
    }
}

struct OCRLine {
    let text: String
    let box: CGRect
}

struct OCRResult {
    let lines: [OCRLine]
    let units: [String]

    var text: String {
        units.joined(separator: "\n")
    }
}

final class VisionOCR {
    private let languages: [String]
    private let useLanguageCorrection: Bool

    init(config: BridgeConfig) {
        self.languages = config.ocrLanguages
        self.useLanguageCorrection = config.useLanguageCorrection
    }

    func recognize(_ imageData: Data) throws -> OCRResult {
        guard let image = NSImage(data: imageData) else {
            throw BridgeError.ocr("无法读取 JPEG/PNG 图片")
        }

        var rect = NSRect(origin: .zero, size: image.size)
        guard let cgImage = image.cgImage(forProposedRect: &rect, context: nil, hints: nil) else {
            throw BridgeError.ocr("无法转换为 CGImage")
        }

        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = languages
        request.usesLanguageCorrection = useLanguageCorrection
        request.minimumTextHeight = 0.012

        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        do {
            try handler.perform([request])
        } catch {
            throw BridgeError.ocr("Vision OCR 失败: \(error.localizedDescription)")
        }

        let observations = request.results ?? []
        if observations.isEmpty {
            throw BridgeError.ocr("未识别到文字")
        }

        let ordered = observations.sorted { lhs, rhs in
            let ly = lhs.boundingBox.midY
            let ry = rhs.boundingBox.midY
            if abs(ly - ry) > 0.035 {
                return ly > ry
            }
            return lhs.boundingBox.minX < rhs.boundingBox.minX
        }

        let lines = ordered.compactMap { observation -> OCRLine? in
            guard let candidate = observation.topCandidates(1).first else { return nil }
            let text = candidate.string.trimmingCharacters(in: .whitespacesAndNewlines)
            return text.isEmpty ? nil : OCRLine(text: text, box: observation.boundingBox)
        }

        if lines.isEmpty {
            throw BridgeError.ocr("OCR 结果为空")
        }

        let units = buildLogicalUnits(lines)
        return OCRResult(lines: lines, units: units.isEmpty ? lines.map { $0.text } : units)
    }

    private func buildLogicalUnits(_ lines: [OCRLine]) -> [String] {
        guard let first = lines.first else { return [] }

        var result: [String] = []
        var current = first.text
        var previous = first

        for line in lines.dropFirst() {
            let verticalGap = max(0, previous.box.minY - line.box.maxY)
            let alignedLeft = abs(previous.box.minX - line.box.minX) < 0.12
            let alignedRight = abs(previous.box.maxX - line.box.maxX) < 0.12
            let startsFreshDialogue = line.text.hasPrefix("「") || line.text.hasPrefix("『")
            let shouldMerge = !endsWithStrongBoundary(current)
                && !startsFreshDialogue
                && verticalGap < 0.10
                && (alignedLeft || alignedRight)

            if shouldMerge {
                current += line.text
            } else {
                result.append(current)
                current = line.text
            }
            previous = line
        }

        result.append(current)
        return result.filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    private func endsWithStrongBoundary(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let last = trimmed.last else { return false }
        return "。！？!?」』…".contains(last)
    }
}

struct SessionKey: Hashable {
    let gameID: String
    let sessionID: String
}

struct SessionHistoryEntry: Codable {
    let sceneID: String
    let translation: String
    let createdAt: Double
}

struct SessionSnapshot {
    let compactMemory: String
    let history: [SessionHistoryEntry]
    let lastOCR: String
    let lastTranslation: String
    let currentSceneID: String
    let compactCount: Int
}

final class SessionStore {
    private struct State: Codable {
        var compactMemory: String = ""
        var history: [SessionHistoryEntry] = []
        var lastOCR: String = ""
        var lastTranslation: String = ""
        var currentSceneID: String = "default"
        var compactCount: Int = 0
    }

    private var states: [SessionKey: State] = [:]
    private var loaded: Set<SessionKey> = []
    private let queue = DispatchQueue(label: "SakuraVisionBridge.SessionStore")
    private let directory: URL
    private let persist: Bool

    init(directory: URL, persist: Bool) throws {
        self.directory = directory
        self.persist = persist
        if persist {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        }
    }

    func snapshot(for key: SessionKey) -> SessionSnapshot {
        queue.sync {
            let state = stateLocked(for: key)
            return snapshot(from: state)
        }
    }

    func updateSuccess(
        for key: SessionKey,
        sceneID: String,
        ocrText: String,
        translation: String,
        historyEnabled: Bool
    ) {
        queue.sync {
            var state = stateLocked(for: key)
            state.lastOCR = normalizeOCR(ocrText)
            state.lastTranslation = translation
            state.currentSceneID = sceneID
            if historyEnabled {
                let value = translation.trimmingCharacters(in: .whitespacesAndNewlines)
                if !value.isEmpty {
                    state.history.append(SessionHistoryEntry(
                        sceneID: sceneID,
                        translation: value,
                        createdAt: Date().timeIntervalSince1970
                    ))
                }
            }
            states[key] = state
            persistLocked(state, for: key)
        }
    }

    func commitCompact(
        for key: SessionKey,
        newMemory: String,
        remainingHistory: [SessionHistoryEntry]
    ) -> SessionSnapshot {
        queue.sync {
            var state = stateLocked(for: key)
            state.compactMemory = newMemory.trimmingCharacters(in: .whitespacesAndNewlines)
            state.history = remainingHistory
            state.compactCount += 1
            states[key] = state
            persistLocked(state, for: key)
            return snapshot(from: state)
        }
    }

    func isSameOCR(_ text: String, sceneID: String, for key: SessionKey) -> Bool {
        let normalized = normalizeOCR(text)
        return queue.sync {
            let state = stateLocked(for: key)
            return !normalized.isEmpty && normalized == state.lastOCR && sceneID == state.currentSceneID
        }
    }

    func reset(_ key: SessionKey) {
        queue.sync {
            states[key] = State()
            loaded.insert(key)
            if persist {
                try? FileManager.default.removeItem(at: fileURL(for: key))
            }
        }
    }

    private func snapshot(from state: State) -> SessionSnapshot {
        SessionSnapshot(
            compactMemory: state.compactMemory,
            history: state.history,
            lastOCR: state.lastOCR,
            lastTranslation: state.lastTranslation,
            currentSceneID: state.currentSceneID,
            compactCount: state.compactCount
        )
    }

    private func stateLocked(for key: SessionKey) -> State {
        if let state = states[key] { return state }
        guard !loaded.contains(key) else { return State() }
        loaded.insert(key)
        guard persist,
              let data = try? Data(contentsOf: fileURL(for: key)),
              let decoded = try? JSONDecoder().decode(State.self, from: data) else {
            let state = State()
            states[key] = state
            return state
        }
        states[key] = decoded
        return decoded
    }

    private func persistLocked(_ state: State, for key: SessionKey) {
        guard persist else { return }
        do {
            let encoder = JSONEncoder()
            encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
            let data = try encoder.encode(state)
            let target = fileURL(for: key)
            let temp = target.appendingPathExtension("tmp")
            try data.write(to: temp, options: .atomic)
            if FileManager.default.fileExists(atPath: target.path) {
                _ = try FileManager.default.replaceItemAt(target, withItemAt: temp)
            } else {
                try FileManager.default.moveItem(at: temp, to: target)
            }
        } catch {
            fputs("Session persist failed: \(error)\n", stderr)
        }
    }

    private func fileURL(for key: SessionKey) -> URL {
        let label = safePart(key.gameID) + "-" + safePart(key.sessionID.prefix(24).description)
        return directory.appendingPathComponent("\(label)-\(stableHash(key.gameID + "|" + key.sessionID)).json")
    }

    private func safePart(_ value: String) -> String {
        let allowed = CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "-_"))
        let mapped = value.unicodeScalars.map { allowed.contains($0) ? String($0) : "_" }.joined()
        return String(mapped.prefix(48)).isEmpty ? "default" : String(mapped.prefix(48))
    }

    private func stableHash(_ value: String) -> String {
        var hash: UInt64 = 1469598103934665603
        for byte in value.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 1099511628211
        }
        return String(format: "%016llx", hash)
    }

    private func normalizeOCR(_ text: String) -> String {
        text.unicodeScalars.filter { !CharacterSet.whitespacesAndNewlines.contains($0) }.map(String.init).joined()
    }
}

struct ValidationResult {
    let ok: Bool
    let errors: [String]
    let warnings: [String]
}

final class TranslationValidator {
    func validate(sourceUnits: [String], output: String) -> ValidationResult {
        let trimmed = output.trimmingCharacters(in: .whitespacesAndNewlines)
        var errors: [String] = []
        var warnings: [String] = []

        if trimmed.isEmpty {
            errors.append("EMPTY_OUTPUT")
            return ValidationResult(ok: false, errors: errors, warnings: warnings)
        }

        let metaPrefixes = ["以下是翻译", "翻译如下", "译文：", "译文:", "好的，以下", "下面是翻译"]
        if metaPrefixes.contains(where: { trimmed.hasPrefix($0) }) {
            errors.append("META_OUTPUT_DETECTED")
        }

        let promptEchoMarkers = [
            "【格式校正要求】",
            "上一次输出未能严格保持原文结构",
            "请重新翻译当前 [Input]",
            "必须逐行对应，不得合并、拆分、漏译或增加行",
            "控制符和占位符必须逐字原样保留",
            "[History]",
            "[Glossary]",
            "[Input]",
            "[Retry]"
        ]
        if promptEchoMarkers.contains(where: { trimmed.contains($0) }) {
            errors.append("PROMPT_ECHO_DETECTED")
        }

        let sourceLineCount = max(1, sourceUnits.count)
        let outputLineCount = normalizedLines(trimmed).count
        if sourceLineCount != outputLineCount {
            warnings.append("LINE_COUNT_MISMATCH")
        }

        let sourceChars = max(1, sourceUnits.joined().count)
        let outputChars = trimmed.count
        let ratio = Double(outputChars) / Double(sourceChars)
        if ratio < 0.25 || ratio > 4.0 {
            warnings.append("LENGTH_RATIO_\(String(format: "%.2f", ratio))")
        }

        let kanaRatio = japaneseKanaRatio(trimmed)
        if kanaRatio > 0.20 {
            warnings.append("POSSIBLE_UNTRANSLATED_TEXT_\(String(format: "%.2f", kanaRatio))")
        }

        let hardFailure = !errors.isEmpty || kanaRatio > 0.20
        return ValidationResult(ok: !hardFailure, errors: errors, warnings: warnings)
    }

    private func normalizedLines(_ text: String) -> [String] {
        text.replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
            .components(separatedBy: "\n")
            .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
    }

    private func japaneseKanaRatio(_ text: String) -> Double {
        var total = 0
        var kana = 0
        for scalar in text.unicodeScalars {
            if CharacterSet.whitespacesAndNewlines.contains(scalar) { continue }
            total += 1
            let v = scalar.value
            if (0x3040...0x309F).contains(v) || (0x30A0...0x30FF).contains(v) || (0x31F0...0x31FF).contains(v) {
                kana += 1
            }
        }
        guard total > 0 else { return 0 }
        return Double(kana) / Double(total)
    }
}

struct LlamaMetrics {
    let tokensCached: Int
    let tokensEvaluated: Int
    let promptMS: Double
    let predictedTokens: Int
    let predictedMS: Double

    static let empty = LlamaMetrics(tokensCached: 0, tokensEvaluated: 0, promptMS: 0, predictedTokens: 0, predictedMS: 0)

    var cacheHitRatio: Double {
        let total = tokensCached + tokensEvaluated
        guard total > 0 else { return 0 }
        return Double(tokensCached) / Double(total)
    }
}

struct TranslationContext {
    let compactMemory: String
    let history: [SessionHistoryEntry]
}

struct TranslationOutput {
    let text: String
    let retries: Int
    let validation: ValidationResult
    let modelFirstMS: Int
    let retryMS: Int
    let validationMS: Int
    let maxTokens: Int
    let llama: LlamaMetrics
}

private struct ModelResult {
    let text: String
    let llama: LlamaMetrics
}

final class OpenAITranslator {
    private let config: BridgeConfig
    private let session: URLSession
    private let validator = TranslationValidator()

    private static let systemPrompt = """
你是视觉小说日中翻译模型。将输入忠实、自然地翻译成简体中文。
要求：
1. 保持人物关系、主客体、人称、否定、时序、数量与事实准确。
2. 不增删、概括或二次创作；保持原文语气和内容强度，不审查或弱化。
3. 保持输入逻辑行数、控制符、占位符和脚本标记。
4. 结合 History 保持称呼、语气和上下文一致。
5. 只输出最终中文译文，不要说明、分析、前后缀或 Markdown。
"""

    private static let compactSystemPrompt = """
你是视觉小说翻译会话压缩器。把旧的翻译历史压缩为长期翻译记忆。
只保留人物、关系、性别/人称、称呼、地点、重要剧情事实、当前指代、固定译名、术语和稳定语言风格。
不要续写剧情，不要添加输入中不存在的事实。输出简洁的结构化中文记忆，不要说明过程。
"""

    init(config: BridgeConfig) {
        self.config = config
        let sessionConfig = URLSessionConfiguration.ephemeral
        sessionConfig.timeoutIntervalForRequest = 90
        sessionConfig.timeoutIntervalForResource = 120
        self.session = URLSession(configuration: sessionConfig)
    }

    func countInputTokens(
        units: [String],
        context: TranslationContext,
        strictRetry: Bool = false,
        completion: @escaping (Result<Int, Error>) -> Void
    ) {
        let messages = translationMessages(units: units, context: context, strictRetry: strictRetry)
        var request = URLRequest(url: config.tokenCountURL)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if !config.apiKey.isEmpty {
            request.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")
        }
        let payload: [String: Any] = ["model": config.model, "messages": messages]
        do {
            request.httpBody = try JSONSerialization.data(withJSONObject: payload, options: [])
        } catch {
            completion(.failure(BridgeError.upstream("生成 token 计数请求失败: \(error.localizedDescription)")))
            return
        }

        session.dataTask(with: request) { data, response, error in
            if let error = error {
                completion(.failure(BridgeError.upstream("llama token 计数失败: \(error.localizedDescription)")))
                return
            }
            guard let http = response as? HTTPURLResponse else {
                completion(.failure(BridgeError.upstream("llama token 计数没有 HTTP 响应")))
                return
            }
            let body = data ?? Data()
            guard (200...299).contains(http.statusCode) else {
                let text = String(data: body, encoding: .utf8) ?? ""
                completion(.failure(BridgeError.upstream("llama token 计数 HTTP \(http.statusCode): \(text)")))
                return
            }
            do {
                guard let root = try JSONSerialization.jsonObject(with: body) as? [String: Any],
                      let count = Self.intValue(root["input_tokens"]) else {
                    throw BridgeError.upstream("llama token 计数响应缺少 input_tokens")
                }
                completion(.success(count))
            } catch {
                completion(.failure(error))
            }
        }.resume()
    }

    func compact(
        existingMemory: String,
        entries: [SessionHistoryEntry],
        completion: @escaping (Result<String, Error>) -> Void
    ) {
        guard !entries.isEmpty else {
            completion(.failure(BridgeError.upstream("没有可压缩的历史")))
            return
        }

        let historyText = entries.map { "[Scene \($0.sceneID)] \($0.translation)" }.joined(separator: "\n")
        let memory = existingMemory.trimmingCharacters(in: .whitespacesAndNewlines)
        let user = """
[Existing Memory]
\(memory.isEmpty ? "(无)" : memory)

[History To Compact]
\(historyText)

请合并为新的长期翻译记忆，尽量精炼；不要保留逐句流水账。
"""
        let messages: [[String: String]] = [
            ["role": "system", "content": Self.compactSystemPrompt],
            ["role": "user", "content": user]
        ]

        callModel(
            url: config.compactURL,
            model: config.compactModel,
            messages: messages,
            temperature: config.compactTemperature,
            maxTokens: config.compactMaxTokens,
            cachePrompt: false
        ) { result in
            switch result {
            case .failure(let error): completion(.failure(error))
            case .success(let model):
                let value = model.text.trimmingCharacters(in: .whitespacesAndNewlines)
                if value.isEmpty {
                    completion(.failure(BridgeError.upstream("compact 返回空内容")))
                } else {
                    completion(.success(value))
                }
            }
        }
    }

    func translate(
        units: [String],
        context: TranslationContext,
        completion: @escaping (Result<TranslationOutput, Error>) -> Void
    ) {
        let tokenBudget = dynamicMaxTokens(units)
        let firstStarted = DispatchTime.now()

        callTranslationModel(
            units: units,
            context: context,
            strictRetry: false,
            temperature: config.temperature,
            maxTokens: tokenBudget
        ) { first in
            let firstMS = self.elapsedMS(since: firstStarted)
            switch first {
            case .failure(let error):
                completion(.failure(error))

            case .success(let firstResult):
                let firstText = firstResult.text
                let validationStarted = DispatchTime.now()
                let firstValidation = self.validator.validate(sourceUnits: units, output: firstText)
                let firstValidationMS = self.elapsedMS(since: validationStarted)

                if !self.shouldRetry(firstValidation) {
                    completion(.success(TranslationOutput(
                        text: firstText,
                        retries: 0,
                        validation: firstValidation,
                        modelFirstMS: firstMS,
                        retryMS: 0,
                        validationMS: firstValidationMS,
                        maxTokens: tokenBudget,
                        llama: firstResult.llama
                    )))
                    return
                }

                let retryStarted = DispatchTime.now()
                self.callTranslationModel(
                    units: units,
                    context: context,
                    strictRetry: true,
                    temperature: self.config.retryTemperature,
                    maxTokens: tokenBudget
                ) { second in
                    let retryMS = self.elapsedMS(since: retryStarted)
                    switch second {
                    case .failure:
                        completion(.success(TranslationOutput(
                            text: firstText,
                            retries: 1,
                            validation: firstValidation,
                            modelFirstMS: firstMS,
                            retryMS: retryMS,
                            validationMS: firstValidationMS,
                            maxTokens: tokenBudget,
                            llama: firstResult.llama
                        )))

                    case .success(let secondResult):
                        let secondText = secondResult.text
                        let secondValidationStarted = DispatchTime.now()
                        let secondValidation = self.validator.validate(sourceUnits: units, output: secondText)
                        let totalValidationMS = firstValidationMS + self.elapsedMS(since: secondValidationStarted)

                        let useSecond = secondValidation.ok || self.validationPenalty(secondValidation) < self.validationPenalty(firstValidation)
                        let chosenText = useSecond ? secondText : firstText
                        let chosenValidation = useSecond ? secondValidation : firstValidation
                        let chosenMetrics = useSecond ? secondResult.llama : firstResult.llama

                        completion(.success(TranslationOutput(
                            text: chosenText,
                            retries: 1,
                            validation: chosenValidation,
                            modelFirstMS: firstMS,
                            retryMS: retryMS,
                            validationMS: totalValidationMS,
                            maxTokens: tokenBudget,
                            llama: chosenMetrics
                        )))
                    }
                }
            }
        }
    }

    private func callTranslationModel(
        units: [String],
        context: TranslationContext,
        strictRetry: Bool,
        temperature: Double,
        maxTokens: Int,
        completion: @escaping (Result<ModelResult, Error>) -> Void
    ) {
        callModel(
            url: config.translateURL,
            model: config.model,
            messages: translationMessages(units: units, context: context, strictRetry: strictRetry),
            temperature: temperature,
            maxTokens: maxTokens,
            cachePrompt: config.cachePrompt,
            completion: completion
        )
    }

    private func callModel(
        url: URL,
        model: String,
        messages: [[String: String]],
        temperature: Double,
        maxTokens: Int,
        cachePrompt: Bool,
        completion: @escaping (Result<ModelResult, Error>) -> Void
    ) {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if !config.apiKey.isEmpty {
            request.setValue("Bearer \(config.apiKey)", forHTTPHeaderField: "Authorization")
        }

        let payload: [String: Any] = [
            "model": model,
            "messages": messages,
            "temperature": temperature,
            "top_p": config.topP,
            "max_tokens": maxTokens,
            "stream": false,
            "cache_prompt": cachePrompt
        ]

        do {
            request.httpBody = try JSONSerialization.data(withJSONObject: payload, options: [])
        } catch {
            completion(.failure(BridgeError.upstream("生成翻译请求失败: \(error.localizedDescription)")))
            return
        }

        session.dataTask(with: request) { [config] data, response, error in
            if let error = error {
                completion(.failure(BridgeError.upstream("翻译服务连接失败: \(error.localizedDescription)")))
                return
            }

            guard let http = response as? HTTPURLResponse else {
                completion(.failure(BridgeError.upstream("翻译服务没有返回 HTTP 响应")))
                return
            }

            let body = data ?? Data()
            guard (200...299).contains(http.statusCode) else {
                let text = String(data: body, encoding: .utf8) ?? ""
                completion(.failure(BridgeError.upstream("翻译服务 HTTP \(http.statusCode): \(text)")))
                return
            }

            do {
                guard let root = try JSONSerialization.jsonObject(with: body) as? [String: Any] else {
                    throw BridgeError.upstream("翻译服务 JSON 格式不正确")
                }

                var content: String?
                if let choices = root["choices"] as? [[String: Any]], let first = choices.first {
                    if let message = first["message"] as? [String: Any] {
                        if let string = message["content"] as? String {
                            content = string
                        } else if let parts = message["content"] as? [[String: Any]] {
                            let strings = parts.compactMap { $0["text"] as? String }
                            if !strings.isEmpty { content = strings.joined(separator: "\n") }
                        }
                    }
                    if content == nil, let text = first["text"] as? String {
                        content = text
                    }
                }

                guard var translated = content?.trimmingCharacters(in: .whitespacesAndNewlines), !translated.isEmpty else {
                    throw BridgeError.upstream("翻译服务返回中没有 choices[0].message.content")
                }

                if config.stripThinkTags {
                    translated = Self.stripThink(translated)
                }
                completion(.success(ModelResult(text: translated, llama: Self.parseLlamaMetrics(root))))
            } catch {
                completion(.failure(error))
            }
        }.resume()
    }

    private func translationMessages(
        units: [String],
        context: TranslationContext,
        strictRetry: Bool
    ) -> [[String: String]] {
        [
            ["role": "system", "content": Self.systemPrompt],
            ["role": "user", "content": buildUserPrompt(units: units, context: context, strictRetry: strictRetry)]
        ]
    }

    private func buildUserPrompt(
        units: [String],
        context: TranslationContext,
        strictRetry: Bool
    ) -> String {
        let memory = context.compactMemory.trimmingCharacters(in: .whitespacesAndNewlines)
        let recent = context.history.isEmpty
            ? "(无)"
            : context.history.map { "[Scene \($0.sceneID)] \($0.translation)" }.joined(separator: "\n")
        let retryBlock = strictRetry
            ? "[Retry]\n上次输出异常。仅翻译 [Input]；逐行对应，不要复述本段。\n\n"
            : ""

        return """
[History]
[Compact Memory]
\(memory.isEmpty ? "(无)" : memory)

[Recent History]
\(recent)

[Glossary]
(无)

\(retryBlock)[Input]
\(units.joined(separator: "\n"))
"""
    }

    private func shouldRetry(_ validation: ValidationResult) -> Bool {
        if !validation.errors.isEmpty { return true }
        return validation.warnings.contains { $0.hasPrefix("POSSIBLE_UNTRANSLATED_TEXT_") }
    }

    private func dynamicMaxTokens(_ units: [String]) -> Int {
        let sourceChars = max(1, units.joined().count)
        let estimate = sourceChars * 3 + 64
        return max(128, min(config.maxTokens, estimate))
    }

    private func elapsedMS(since start: DispatchTime) -> Int {
        let nanos = DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds
        return Int(nanos / 1_000_000)
    }

    private func validationPenalty(_ validation: ValidationResult) -> Int {
        var score = 0
        for error in validation.errors {
            switch error {
            case "EMPTY_OUTPUT": score += 1000
            case "PROMPT_ECHO_DETECTED": score += 900
            case "META_OUTPUT_DETECTED": score += 500
            case "LINE_COUNT_MISMATCH": score += 120
            default: score += 100
            }
        }
        score += validation.warnings.count * 20
        return score
    }

    private static func parseLlamaMetrics(_ root: [String: Any]) -> LlamaMetrics {
        let timings = root["timings"] as? [String: Any] ?? [:]
        let usage = root["usage"] as? [String: Any] ?? [:]
        let cached = intValue(root["tokens_cached"])
            ?? intValue(timings["cache_n"])
            ?? intValue((usage["prompt_tokens_details"] as? [String: Any])?["cached_tokens"])
            ?? 0
        let evaluated = intValue(root["tokens_evaluated"])
            ?? intValue(timings["prompt_n"])
            ?? intValue(usage["prompt_tokens"])
            ?? 0
        let predicted = intValue(timings["predicted_n"])
            ?? intValue(usage["completion_tokens"])
            ?? 0
        return LlamaMetrics(
            tokensCached: cached,
            tokensEvaluated: evaluated,
            promptMS: doubleValue(timings["prompt_ms"]) ?? 0,
            predictedTokens: predicted,
            predictedMS: doubleValue(timings["predicted_ms"]) ?? 0
        )
    }

    private static func intValue(_ value: Any?) -> Int? {
        if let v = value as? Int { return v }
        if let v = value as? NSNumber { return v.intValue }
        if let v = value as? String { return Int(v) }
        return nil
    }

    private static func doubleValue(_ value: Any?) -> Double? {
        if let v = value as? Double { return v }
        if let v = value as? NSNumber { return v.doubleValue }
        if let v = value as? String { return Double(v) }
        return nil
    }

    private static func stripThink(_ input: String) -> String {
        guard let regex = try? NSRegularExpression(pattern: "(?is)<think>.*?</think>", options: []) else {
            return input
        }
        let range = NSRange(input.startIndex..<input.endIndex, in: input)
        let output = regex.stringByReplacingMatches(in: input, options: [], range: range, withTemplate: "")
        return output.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

struct HTTPRequest {
    let method: String
    let path: String
    let headers: [String: String]
    let body: Data
}

final class HTTPConnection {
    private let connection: NWConnection
    private let config: BridgeConfig
    private let handler: (HTTPRequest, @escaping (Int, [String: Any]) -> Void) -> Void
    private var buffer = Data()
    private var headerEnd: Int?
    private var contentLength = 0
    private var parsedMethod = ""
    private var parsedPath = ""
    private var parsedHeaders: [String: String] = [:]
    private var finished = false

    init(
        connection: NWConnection,
        config: BridgeConfig,
        handler: @escaping (HTTPRequest, @escaping (Int, [String: Any]) -> Void) -> Void
    ) {
        self.connection = connection
        self.config = config
        self.handler = handler
    }

    func start(on queue: DispatchQueue) {
        connection.start(queue: queue)
        receive()
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) { data, _, isComplete, error in
            guard !self.finished else { return }
            if let data = data, !data.isEmpty {
                self.buffer.append(data)
            }

            do {
                if self.headerEnd == nil {
                    try self.parseHeaderIfReady()
                }
                if let end = self.headerEnd {
                    let available = self.buffer.count - end
                    if self.contentLength > self.config.maxImageBytes {
                        throw BridgeError.badRequest("图片过大，最大 \(self.config.maxImageBytes) bytes")
                    }
                    if available >= self.contentLength {
                        let body = self.buffer.subdata(in: end..<(end + self.contentLength))
                        self.finished = true
                        let request = HTTPRequest(
                            method: self.parsedMethod,
                            path: self.parsedPath,
                            headers: self.parsedHeaders,
                            body: body
                        )
                        self.handler(request) { status, json in
                            self.send(status: status, json: json)
                        }
                        return
                    }
                }
            } catch {
                self.finished = true
                self.send(status: 400, json: ["ok": false, "error": String(describing: error)])
                return
            }

            if let error = error {
                self.finished = true
                self.send(status: 400, json: ["ok": false, "error": error.localizedDescription])
                return
            }
            if isComplete {
                self.finished = true
                self.send(status: 400, json: ["ok": false, "error": "请求提前结束"])
                return
            }
            self.receive()
        }
    }

    private func parseHeaderIfReady() throws {
        let delimiter = Data("\r\n\r\n".utf8)
        guard let range = buffer.range(of: delimiter) else {
            if buffer.count > 64 * 1024 {
                throw BridgeError.badRequest("HTTP header 过大")
            }
            return
        }

        let headerData = buffer.subdata(in: 0..<range.lowerBound)
        guard let headerText = String(data: headerData, encoding: .utf8) else {
            throw BridgeError.badRequest("HTTP header 不是 UTF-8")
        }

        let lines = headerText.components(separatedBy: "\r\n")
        guard let first = lines.first else {
            throw BridgeError.badRequest("缺少请求行")
        }
        let parts = first.split(separator: " ")
        guard parts.count >= 2 else {
            throw BridgeError.badRequest("请求行格式错误")
        }

        parsedMethod = String(parts[0]).uppercased()
        parsedPath = String(parts[1]).components(separatedBy: "?").first ?? String(parts[1])

        var headers: [String: String] = [:]
        for line in lines.dropFirst() {
            guard let colon = line.firstIndex(of: ":") else { continue }
            let key = line[..<colon].trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            let value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespacesAndNewlines)
            headers[key] = value
        }
        parsedHeaders = headers

        if parsedMethod == "POST" {
            let lengthText = headers["content-length"] ?? "0"
            guard let length = Int(lengthText), length >= 0 else {
                throw BridgeError.badRequest("Content-Length 无效")
            }
            contentLength = length
        } else {
            contentLength = 0
        }
        headerEnd = range.upperBound
    }

    private func send(status: Int, json: [String: Any]) {
        let reason: String
        switch status {
        case 200: reason = "OK"
        case 400: reason = "Bad Request"
        case 401: reason = "Unauthorized"
        case 404: reason = "Not Found"
        case 413: reason = "Payload Too Large"
        case 502: reason = "Bad Gateway"
        default: reason = "Internal Server Error"
        }

        let body: Data
        do {
            body = try JSONSerialization.data(withJSONObject: json, options: [])
        } catch {
            body = Data("{\"ok\":false,\"error\":\"json encode failed\"}".utf8)
        }

        var head = "HTTP/1.1 \(status) \(reason)\r\n"
        head += "Content-Type: application/json; charset=utf-8\r\n"
        head += "Content-Length: \(body.count)\r\n"
        head += "Connection: close\r\n"
        head += "Cache-Control: no-store\r\n\r\n"

        var packet = Data(head.utf8)
        packet.append(body)
        connection.send(content: packet, completion: .contentProcessed { _ in
            self.connection.cancel()
        })
    }
}

final class SakuraBridgeServer {
    private let config: BridgeConfig
    private let ocr: VisionOCR
    private let translator: OpenAITranslator
    private let sessions: SessionStore
    private let listener: NWListener
    private let networkQueue = DispatchQueue(label: "SakuraVisionBridge.Network")
    private let ocrQueue = DispatchQueue(label: "SakuraVisionBridge.OCR", qos: .userInitiated)

    init(config: BridgeConfig) throws {
        self.config = config
        self.ocr = VisionOCR(config: config)
        self.translator = OpenAITranslator(config: config)
        self.sessions = try SessionStore(directory: config.sessionDirectory, persist: config.sessionPersist)
        guard let port = NWEndpoint.Port(rawValue: config.port) else {
            throw BridgeError.config("BRIDGE_PORT 无效")
        }
        self.listener = try NWListener(using: .tcp, on: port)
    }

    func run() {
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                print("Sakura Vision Bridge Phase B ready")
                print("  Listen: http://0.0.0.0:\(self.config.port)")
                print("  Health: http://127.0.0.1:\(self.config.port)/health")
                print("  Translate: POST /translate-image")
                print("  Reset context: POST /reset-context")
                print("  OCR languages: \(self.config.ocrLanguages.joined(separator: ", "))")
                print("  Upstream: \(self.config.translateURL.absoluteString)")
                print("  Model: \(self.config.model)")
                print("  Prompt: \(self.config.promptVersion)")
                print("  Bridge key: \(self.config.bridgeKey.isEmpty ? "disabled" : "enabled")")
            case .failed(let error):
                fputs("Listener failed: \(error)\n", stderr)
                exit(1)
            default:
                break
            }
        }

        listener.newConnectionHandler = { [weak self] connection in
            guard let self = self else { return }
            let client = HTTPConnection(connection: connection, config: self.config) { request, reply in
                self.handle(request: request, reply: reply)
            }
            client.start(on: self.networkQueue)
        }

        listener.start(queue: networkQueue)
        dispatchMain()
    }

    private func handle(request: HTTPRequest, reply: @escaping (Int, [String: Any]) -> Void) {
        if request.method == "GET" && request.path == "/health" {
            reply(200, [
                "ok": true,
                "service": "sakura-vision-bridge",
                "phase": "B",
                "ocr": "macOS Vision",
                "model": config.model,
                "prompt_version": config.promptVersion,
                "context_size": config.contextSize,
                "compact_trigger_tokens": config.compactTriggerTokens,
                "session_persist": config.sessionPersist,
                "session_dir": config.sessionDirectory.path
            ])
            return
        }

        guard authorize(request) else {
            reply(401, ["ok": false, "error": "invalid bridge key"])
            return
        }

        if request.method == "POST" && request.path == "/reset-context" {
            let key = sessionKey(from: request)
            sessions.reset(key)
            reply(200, ["ok": true, "reset": true])
            return
        }

        guard request.method == "POST", request.path == "/translate-image" else {
            reply(404, ["ok": false, "error": "not found"])
            return
        }

        let contentType = request.headers["content-type"]?.lowercased() ?? ""
        guard contentType.hasPrefix("image/jpeg") || contentType.hasPrefix("image/png") || contentType.isEmpty else {
            reply(400, ["ok": false, "error": "Content-Type 必须是 image/jpeg 或 image/png"])
            return
        }

        guard !request.body.isEmpty else {
            reply(400, ["ok": false, "error": "图片为空"])
            return
        }

        if request.body.count > config.maxImageBytes {
            reply(413, ["ok": false, "error": "图片过大"])
            return
        }

        let started = DispatchTime.now()
        let key = sessionKey(from: request)
        let sceneID = sceneID(from: request)
        let autoMode = boolHeader(request, "x-auto-mode", fallback: false)
        let forceRetranslate = boolHeader(request, "x-force-retranslate", fallback: false)
        let historyEnabled = boolHeader(request, "x-context-enabled", fallback: true)

        ocrQueue.async {
            let ocrStarted = DispatchTime.now()
            do {
                let ocrResult = try self.ocr.recognize(request.body)
                let ocrMS = self.elapsedMS(since: ocrStarted)
                let japanese = ocrResult.text
                print("OCR [\(key.gameID)/\(key.sessionID)/\(sceneID)]: \(japanese.replacingOccurrences(of: "\n", with: " / "))")

                if autoMode && !forceRetranslate && self.sessions.isSameOCR(japanese, sceneID: sceneID, for: key) {
                    let snapshot = self.sessions.snapshot(for: key)
                    reply(200, [
                        "ok": true,
                        "changed": false,
                        "ocr_text": japanese,
                        "translation": snapshot.lastTranslation,
                        "meta": [
                            "history_used": historyEnabled && (!snapshot.history.isEmpty || !snapshot.compactMemory.isEmpty),
                            "history_entries": snapshot.history.count,
                            "compact_count": snapshot.compactCount,
                            "retry_count": 0,
                            "unit_count": ocrResult.units.count,
                            "ocr_ms": ocrMS,
                            "model_first_ms": 0,
                            "retry_ms": 0,
                            "validation_ms": 0,
                            "max_tokens": 0,
                            "total_ms": self.elapsedMS(since: started),
                            "latency_ms": self.elapsedMS(since: started),
                            "prompt_version": self.config.promptVersion,
                            "auto_mode": true
                        ]
                    ])
                    return
                }

                self.prepareContext(
                    units: ocrResult.units,
                    key: key,
                    historyEnabled: historyEnabled
                ) { prepared in
                    switch prepared {
                    case .failure(let error):
                        reply(502, [
                            "ok": false,
                            "ocr_text": japanese,
                            "error": String(describing: error),
                            "meta": [
                                "latency_ms": self.elapsedMS(since: started),
                                "prompt_version": self.config.promptVersion
                            ]
                        ])

                    case .success(let contextInfo):
                        self.translator.translate(units: ocrResult.units, context: contextInfo.context) { result in
                            switch result {
                            case .success(let output):
                                self.sessions.updateSuccess(
                                    for: key,
                                    sceneID: sceneID,
                                    ocrText: japanese,
                                    translation: output.text,
                                    historyEnabled: historyEnabled
                                )
                                let finalSnapshot = self.sessions.snapshot(for: key)
                                let contextPercent = Double(contextInfo.inputTokens) / Double(max(1, self.config.contextSize))

                                reply(200, [
                                    "ok": true,
                                    "changed": true,
                                    "ocr_text": japanese,
                                    "translation": output.text,
                                    "meta": [
                                        "history_used": historyEnabled && (!contextInfo.context.history.isEmpty || !contextInfo.context.compactMemory.isEmpty),
                                        "history_entries": finalSnapshot.history.count,
                                        "compact_count": finalSnapshot.compactCount,
                                        "compacted": contextInfo.compacted,
                                        "context_input_tokens": contextInfo.inputTokens,
                                        "context_percent": contextPercent,
                                        "retry_count": output.retries,
                                        "unit_count": ocrResult.units.count,
                                        "source_chars": japanese.count,
                                        "output_chars": output.text.count,
                                        "validation_ok": output.validation.ok,
                                        "validation_errors": output.validation.errors,
                                        "validation_warnings": output.validation.warnings,
                                        "ocr_ms": ocrMS,
                                        "model_first_ms": output.modelFirstMS,
                                        "retry_ms": output.retryMS,
                                        "validation_ms": output.validationMS,
                                        "max_tokens": output.maxTokens,
                                        "llama_tokens_cached": output.llama.tokensCached,
                                        "llama_tokens_evaluated": output.llama.tokensEvaluated,
                                        "llama_prompt_ms": output.llama.promptMS,
                                        "llama_predicted_tokens": output.llama.predictedTokens,
                                        "llama_predicted_ms": output.llama.predictedMS,
                                        "llama_cache_hit_ratio": output.llama.cacheHitRatio,
                                        "total_ms": self.elapsedMS(since: started),
                                        "latency_ms": self.elapsedMS(since: started),
                                        "prompt_version": self.config.promptVersion,
                                        "auto_mode": autoMode
                                    ]
                                ])
                            case .failure(let error):
                                reply(502, [
                                    "ok": false,
                                    "ocr_text": japanese,
                                    "error": String(describing: error),
                                    "meta": [
                                        "context_input_tokens": contextInfo.inputTokens,
                                        "compacted": contextInfo.compacted,
                                        "latency_ms": self.elapsedMS(since: started),
                                        "prompt_version": self.config.promptVersion
                                    ]
                                ])
                            }
                        }
                    }
                }
            } catch {
                reply(400, [
                    "ok": false,
                    "error": String(describing: error),
                    "meta": ["latency_ms": self.elapsedMS(since: started)]
                ])
            }
        }
    }

    private struct PreparedContext {
        let context: TranslationContext
        let inputTokens: Int
        let compacted: Bool
    }

    private func prepareContext(
        units: [String],
        key: SessionKey,
        historyEnabled: Bool,
        completion: @escaping (Result<PreparedContext, Error>) -> Void
    ) {
        let snapshot = sessions.snapshot(for: key)
        let initialContext = historyEnabled
            ? TranslationContext(compactMemory: snapshot.compactMemory, history: snapshot.history)
            : TranslationContext(compactMemory: "", history: [])

        translator.countInputTokens(units: units, context: initialContext) { counted in
            switch counted {
            case .failure(let error):
                completion(.failure(error))
            case .success(let inputTokens):
                guard historyEnabled, inputTokens >= self.config.compactTriggerTokens else {
                    completion(.success(PreparedContext(context: initialContext, inputTokens: inputTokens, compacted: false)))
                    return
                }

                let keepCount = inputTokens >= self.config.compactHardTokens
                    ? self.config.hardRecentHistoryEntries
                    : self.config.recentHistoryEntries
                let oldCount = max(0, snapshot.history.count - keepCount)
                guard oldCount > 0 else {
                    if inputTokens >= self.config.compactHardTokens {
                        completion(.failure(BridgeError.upstream("会话上下文已接近硬上限且没有足够旧历史可压缩，请新建会话或增大 LLAMA_CTX_SIZE")))
                    } else {
                        completion(.success(PreparedContext(context: initialContext, inputTokens: inputTokens, compacted: false)))
                    }
                    return
                }

                let oldEntries = Array(snapshot.history.prefix(oldCount))
                let remaining = Array(snapshot.history.suffix(snapshot.history.count - oldCount))
                self.translator.compact(existingMemory: snapshot.compactMemory, entries: oldEntries) { compacted in
                    switch compacted {
                    case .failure(let error):
                        if inputTokens >= self.config.compactHardTokens {
                            completion(.failure(error))
                        } else {
                            completion(.success(PreparedContext(context: initialContext, inputTokens: inputTokens, compacted: false)))
                        }
                    case .success(let memory):
                        let candidate = TranslationContext(compactMemory: memory, history: remaining)
                        self.translator.countInputTokens(units: units, context: candidate) { recounted in
                            switch recounted {
                            case .failure(let error):
                                completion(.failure(error))
                            case .success(let newTokens):
                                guard newTokens < inputTokens else {
                                    if inputTokens >= self.config.compactHardTokens {
                                        completion(.failure(BridgeError.upstream("compact 未降低上下文 token 数")))
                                    } else {
                                        completion(.success(PreparedContext(context: initialContext, inputTokens: inputTokens, compacted: false)))
                                    }
                                    return
                                }
                                if newTokens >= self.config.compactHardTokens {
                                    completion(.failure(BridgeError.upstream("compact 后上下文仍超过硬限制，请增大 LLAMA_CTX_SIZE 或新建会话")))
                                    return
                                }
                                _ = self.sessions.commitCompact(for: key, newMemory: memory, remainingHistory: remaining)
                                completion(.success(PreparedContext(context: candidate, inputTokens: newTokens, compacted: true)))
                            }
                        }
                    }
                }
            }
        }
    }

    private func authorize(_ request: HTTPRequest) -> Bool {
        if config.bridgeKey.isEmpty { return true }
        return (request.headers["x-bridge-key"] ?? "") == config.bridgeKey
    }

    private func sessionKey(from request: HTTPRequest) -> SessionKey {
        let game = cleanID(request.headers["x-game-id"] ?? "default")
        let session = cleanID(request.headers["x-session-id"] ?? "default")
        return SessionKey(gameID: game, sessionID: session)
    }

    private func sceneID(from request: HTTPRequest) -> String {
        cleanID(request.headers["x-scene-id"] ?? "default")
    }

    private func cleanID(_ value: String) -> String {
        let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? "default" : String(trimmed.prefix(128))
    }

    private func boolHeader(_ request: HTTPRequest, _ key: String, fallback: Bool) -> Bool {
        guard let raw = request.headers[key]?.lowercased() else { return fallback }
        if ["1", "true", "yes", "on"].contains(raw) { return true }
        if ["0", "false", "no", "off"].contains(raw) { return false }
        return fallback
    }

    private func elapsedMS(since start: DispatchTime) -> Int {
        let nanos = DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds
        return Int(nanos / 1_000_000)
    }
}

@main
struct SakuraVisionBridgeMain {
    static func main() {
        do {
            let config = try BridgeConfig.load()
            let server = try SakuraBridgeServer(config: config)
            server.run()
        } catch {
            fputs("Sakura Vision Bridge 启动失败: \(error)\n", stderr)
            exit(1)
        }
    }
}
