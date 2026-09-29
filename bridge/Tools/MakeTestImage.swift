import AppKit
import Foundation

let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "/tmp/sakura-phaseb-test.jpg"
let size = NSSize(width: 1200, height: 320)
let image = NSImage(size: size)
image.lockFocus()
NSColor.white.setFill()
NSBezierPath(rect: NSRect(origin: .zero, size: size)).fill()
let paragraph = NSMutableParagraphStyle()
paragraph.alignment = .center
let attrs: [NSAttributedString.Key: Any] = [
    .font: NSFont.systemFont(ofSize: 72, weight: .medium),
    .foregroundColor: NSColor.black,
    .paragraphStyle: paragraph
]
("こんにちは、世界！" as NSString).draw(
    in: NSRect(x: 30, y: 100, width: 1140, height: 120),
    withAttributes: attrs
)
image.unlockFocus()
var rect = NSRect(origin: .zero, size: size)
guard let cg = image.cgImage(forProposedRect: &rect, context: nil, hints: nil) else { exit(2) }
let rep = NSBitmapImageRep(cgImage: cg)
guard let data = rep.representation(using: .jpeg, properties: [.compressionFactor: 0.9]) else { exit(3) }
try data.write(to: URL(fileURLWithPath: out))
print(out)
