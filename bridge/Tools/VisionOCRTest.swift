import Foundation
import Vision
import AppKit

guard CommandLine.arguments.count >= 2 else {
    fputs("usage: vision-ocr <image>\n", stderr)
    exit(2)
}
let url = URL(fileURLWithPath: CommandLine.arguments[1])
guard let image = NSImage(contentsOf: url) else {
    fputs("cannot read image\n", stderr)
    exit(3)
}
var rect = NSRect(origin: .zero, size: image.size)
guard let cgImage = image.cgImage(forProposedRect: &rect, context: nil, hints: nil) else {
    fputs("cannot create CGImage\n", stderr)
    exit(4)
}
let request = VNRecognizeTextRequest()
request.recognitionLevel = .accurate
request.recognitionLanguages = ["ja-JP"]
request.usesLanguageCorrection = false
do {
    try VNImageRequestHandler(cgImage: cgImage, options: [:]).perform([request])
    let observations = (request.results ?? []).sorted {
        if abs($0.boundingBox.midY - $1.boundingBox.midY) > 0.035 {
            return $0.boundingBox.midY > $1.boundingBox.midY
        }
        return $0.boundingBox.minX < $1.boundingBox.minX
    }
    for item in observations {
        if let text = item.topCandidates(1).first?.string { print(text) }
    }
} catch {
    fputs("OCR error: \(error)\n", stderr)
    exit(5)
}
