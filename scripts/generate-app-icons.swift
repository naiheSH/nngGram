// Render the established paper-plane mark. Checked-in assets need no Swift on CI.
import AppKit
import Foundation

guard CommandLine.arguments.count == 2 else {
    fatalError("Usage: swift scripts/generate-app-icons.swift <output-directory>")
}
let output = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
try FileManager.default.createDirectory(at: output, withIntermediateDirectories: true)
let iconset = output.appendingPathComponent("nnggram.iconset", isDirectory: true)
try FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)
func render(_ side: Int) -> Data {
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: side, pixelsHigh: side,
        bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
        colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    let context = NSGraphicsContext(bitmapImageRep: bitmap)!.cgContext
    context.scaleBy(x: CGFloat(side) / 1024, y: CGFloat(side) / 1024)
    context.translateBy(x: 0, y: 1024); context.scaleBy(x: 1, y: -1)
    let blue = CGColor(red: 0.196, green: 0.624, blue: 1, alpha: 1)
    let dark = CGColor(red: 0.192, green: 0.361, blue: 0.902, alpha: 1)
    context.saveGState()
    context.addPath(CGPath(roundedRect: CGRect(x: 104, y: 104, width: 816, height: 816), cornerWidth: 184, cornerHeight: 184, transform: nil))
    context.clip()
    context.drawLinearGradient(CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: [blue, dark] as CFArray, locations: [0, 1])!, start: CGPoint(x: 512, y: 104), end: CGPoint(x: 512, y: 920), options: [])
    context.restoreGState()
    context.beginPath()
    let points: [CGPoint] = [.init(x: 238, y: 470), .init(x: 792, y: 258), .init(x: 650, y: 790), .init(x: 500, y: 590), .init(x: 368, y: 708), .init(x: 394, y: 514)]
    context.move(to: points[0]); points.dropFirst().forEach { context.addLine(to: $0) }; context.closePath()
    context.setFillColor(CGColor(gray: 1, alpha: 1)); context.fillPath()
    context.move(to: CGPoint(x: 394, y: 514)); context.addLine(to: CGPoint(x: 690, y: 340)); context.addLine(to: CGPoint(x: 500, y: 590))
    context.setStrokeColor(dark); context.setLineWidth(22); context.setLineJoin(.round); context.setLineCap(.round); context.strokePath()
    return bitmap.representation(using: .png, properties: [:])!
}
for size in [16, 32, 128, 256, 512] {
    try render(size).write(to: iconset.appendingPathComponent("icon_\(size)x\(size).png"))
    try render(size * 2).write(to: iconset.appendingPathComponent("icon_\(size)x\(size)@2x.png"))
}
try render(1024).write(to: output.appendingPathComponent("nnggram.png"))
let iconutil = Process()
iconutil.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
iconutil.arguments = ["-c", "icns", iconset.path, "-o", output.appendingPathComponent("nnggram.icns").path]
try iconutil.run()
iconutil.waitUntilExit()
precondition(iconutil.terminationStatus == 0, "iconutil failed")

// ICO supports embedded PNG, including the 256px representation.
var ico = Data()
func append16(_ value: UInt16) { var little = value.littleEndian; withUnsafeBytes(of: &little) { ico.append(contentsOf: $0) } }
func append32(_ value: UInt32) { var little = value.littleEndian; withUnsafeBytes(of: &little) { ico.append(contentsOf: $0) } }
let sizes = [16, 32, 128, 256]
let images = sizes.map(render)
append16(0); append16(1); append16(UInt16(sizes.count))
var offset = UInt32(6 + 16 * sizes.count)
for (size, image) in zip(sizes, images) {
    ico.append(UInt8(size == 256 ? 0 : size)); ico.append(UInt8(size == 256 ? 0 : size))
    ico.append(0); ico.append(0); append16(1); append16(32)
    append32(UInt32(image.count)); append32(offset)
    offset += UInt32(image.count)
}
images.forEach { ico.append($0) }
try ico.write(to: output.appendingPathComponent("nnggram.ico"))
