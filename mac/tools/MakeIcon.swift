// Draws the app icon (a black smiley on white) at every size macOS wants, into
// an ".iconset" folder. build.sh then turns that folder into AppIcon.icns.
//
//   swift tools/MakeIcon.swift build/AppIcon.iconset
//
// LEARN: drawing the icon from shapes (instead of shipping a picture) keeps it
// sharp at every size, from 16 px in a list to 1024 px in Finder. It uses
// CoreGraphics only (no window needed), so it also runs on GitHub's Mac.
//
// The design matches the Android icon: a 100 x 100 grid, eyes at (37, 42) and
// (63, 42), a smile from (32, 60) to (68, 60) pulled down to (50, 76).
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let out = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "AppIcon.iconset")
try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)

func draw(_ px: Int) -> CGImage {
    let ctx = CGContext(data: nil, width: px, height: px, bitsPerComponent: 8, bytesPerRow: 0,
                        space: CGColorSpace(name: CGColorSpace.sRGB)!,
                        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    let s = CGFloat(px) / 1024
    // CoreGraphics counts y from the bottom; flip so (0, 0) is the top-left like the design.
    ctx.translateBy(x: 0, y: CGFloat(px))
    ctx.scaleBy(x: s, y: -s)

    // Apple's icon grid: the rounded square is 824 of the 1024 pixels, with a
    // 100-pixel margin all round (room for the Dock's shadow and highlight).
    let body = CGRect(x: 100, y: 100, width: 824, height: 824)
    ctx.addPath(CGPath(roundedRect: body, cornerWidth: 185, cornerHeight: 185, transform: nil))
    ctx.setFillColor(CGColor(gray: 1, alpha: 1))
    ctx.fillPath()

    // Design units (0–100) → pixels inside the rounded square.
    func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: 100 + x * 8.24, y: 100 + y * 8.24) }
    let unit: CGFloat = 8.24
    ctx.setFillColor(CGColor(gray: 0, alpha: 1))
    for c in [p(37, 42), p(63, 42)] {
        ctx.fillEllipse(in: CGRect(x: c.x - 6 * unit, y: c.y - 6 * unit, width: 12 * unit, height: 12 * unit))
    }
    ctx.setStrokeColor(CGColor(gray: 0, alpha: 1))
    ctx.setLineWidth(6 * unit)
    ctx.setLineCap(.round)
    ctx.move(to: p(32, 60))
    ctx.addQuadCurve(to: p(68, 60), control: p(50, 76))
    ctx.strokePath()
    return ctx.makeImage()!
}

func save(_ img: CGImage, _ name: String) {
    let url = out.appendingPathComponent(name)
    let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, img, nil)
    CGImageDestinationFinalize(dest)
}

// Each size twice: normal and "@2x" for Retina screens.
for size in [16, 32, 128, 256, 512] {
    save(draw(size), "icon_\(size)x\(size).png")
    save(draw(size * 2), "icon_\(size)x\(size)@2x.png")
}
print("icon written to \(out.path)")
