package arx.display.ascii

import arx.core.*
import arx.display.ascii.Ascii.BoxPieces
import kotlin.math.sign

object AsciiBox {

    val LineBoxPieceChars = CharArray(14).apply {
        this[BoxPieces.None.ordinal] = ' '
        this[BoxPieces.TopLeft.ordinal] = '┌'
        this[BoxPieces.TopRight.ordinal] = '┐'
        this[BoxPieces.BottomLeft.ordinal] = '└'
        this[BoxPieces.BottomRight.ordinal] = '┘'
        this[BoxPieces.HorizontalBottom.ordinal] = '─'
        this[BoxPieces.HorizontalTop.ordinal] = '─'
        this[BoxPieces.VerticalLeft.ordinal] = '│'
        this[BoxPieces.VerticalRight.ordinal] = '│'
        this[BoxPieces.Cross.ordinal] = '┼'
        this[BoxPieces.RightJoin.ordinal] = '┤'
        this[BoxPieces.LeftJoin.ordinal] = '├'
        this[BoxPieces.TopJoin.ordinal] = '┬'
        this[BoxPieces.BottomJoin.ordinal] = '┴'
    }

    val SolidExternalBoxPieceChars = CharArray(14).apply {
        this[BoxPieces.None.ordinal] = ' '
        this[BoxPieces.TopLeft.ordinal] = '█'
        this[BoxPieces.TopRight.ordinal] = '█'
        this[BoxPieces.BottomLeft.ordinal] = '█'
        this[BoxPieces.BottomRight.ordinal] = '█'
        this[BoxPieces.HorizontalTop.ordinal] = '▀'
        this[BoxPieces.HorizontalBottom.ordinal] = '▄'
        this[BoxPieces.VerticalLeft.ordinal] = '█'
        this[BoxPieces.VerticalRight.ordinal] = '█'
        this[BoxPieces.Cross.ordinal] = '█'
        this[BoxPieces.RightJoin.ordinal] = '▌'
        this[BoxPieces.LeftJoin.ordinal] = '▐'
        this[BoxPieces.TopJoin.ordinal] = '█'
        this[BoxPieces.BottomJoin.ordinal] = '█'
    }

    val SolidInternalBoxPieceChars = CharArray(14).apply {
        this[BoxPieces.None.ordinal] = ' '
        this[BoxPieces.TopLeft.ordinal] = '▄'
        this[BoxPieces.TopRight.ordinal] = '▄'
        this[BoxPieces.BottomLeft.ordinal] = '▀'
        this[BoxPieces.BottomRight.ordinal] = '▀'
        this[BoxPieces.HorizontalTop.ordinal] = '▄'
        this[BoxPieces.HorizontalBottom.ordinal] = '▀'
        this[BoxPieces.VerticalLeft.ordinal] = '█'
        this[BoxPieces.VerticalRight.ordinal] = '█'
        this[BoxPieces.Cross.ordinal] = '█'
        this[BoxPieces.RightJoin.ordinal] = '▌'
        this[BoxPieces.LeftJoin.ordinal] = '▐'
        this[BoxPieces.TopJoin.ordinal] = '█'
        this[BoxPieces.BottomJoin.ordinal] = '█'
    }

    val BoxPieceChars = Array(Ascii.BoxStyle.values().size) {
        when (it) {
            Ascii.BoxStyle.Line.ordinal -> LineBoxPieceChars
            Ascii.BoxStyle.SolidExternal.ordinal -> SolidExternalBoxPieceChars
            Ascii.BoxStyle.SolidInternal.ordinal -> SolidInternalBoxPieceChars
            Ascii.BoxStyle.CornersInternal.ordinal -> SolidInternalBoxPieceChars
            else -> throw java.lang.IllegalStateException()
        }
    }

    fun drawBox(c: AsciiCanvas, position: Vec3i, dimensions: Vec2i, style: Ascii.BoxStyle, scale: Int, edgeColor: RGBA, fillColor: RGBA?, join: Boolean) {
        val x = position.x
        val y = position.y
        val z = position.z
        val width = dimensions.x
        val height = dimensions.y

        val farX = x + width - scale
        val farY = y + height - scale
        val edgeColorIdx = c.palette.encode(edgeColor)
        val bgColorIdx = c.palette.encode(fillColor ?: Clear)
        val clearColorIdx = c.palette.encode(Clear)
        val edgeBgColorIdx = tern(style == Ascii.BoxStyle.SolidInternal, clearColorIdx, bgColorIdx)


        val boxChars = BoxPieceChars[style.ordinal]

        // + - - +
        //01234567

        if (style == Ascii.BoxStyle.CornersInternal) {
            c.writeScaled(x + scale, y, z, boxChars[BoxPieces.HorizontalBottom.ordinal], scale, edgeColorIdx, edgeBgColorIdx)
            c.writeScaled(farX - scale, y, z, boxChars[BoxPieces.HorizontalBottom.ordinal], scale, edgeColorIdx, edgeBgColorIdx)

            c.writeScaled(x + scale, farY, z, boxChars[BoxPieces.HorizontalTop.ordinal], scale, edgeColorIdx, edgeBgColorIdx)
            c.writeScaled(farX - scale, farY, z, boxChars[BoxPieces.HorizontalTop.ordinal], scale, edgeColorIdx, edgeBgColorIdx)

            c.writeScaled(x, y + scale, z, boxChars[BoxPieces.TopLeft.ordinal], scale, edgeColorIdx, edgeBgColorIdx)
            c.writeScaled(x, farY - scale, z, boxChars[BoxPieces.BottomLeft.ordinal], scale, edgeColorIdx, edgeBgColorIdx)

            c.writeScaled(farX, y + scale, z, boxChars[BoxPieces.TopRight.ordinal], scale, edgeColorIdx, edgeBgColorIdx)
            c.writeScaled(farX, farY - scale, z, boxChars[BoxPieces.BottomRight.ordinal], scale, edgeColorIdx, edgeBgColorIdx)
        } else {
            for (ax in x + scale until farX step scale) {
                if (join) {
                    //                joinChar(c, ax, y, z.int8, BoxPieces.Horizontal, colorIndex)
                    //                joinChar(c, ax, farY, z.int8, BoxPieces.Horizontal, colorIndex)
                } else {
                    c.writeScaled(ax, y, z, boxChars[BoxPieces.HorizontalBottom.ordinal], scale, edgeColorIdx, edgeBgColorIdx)
                    c.writeScaled(ax, farY, z, boxChars[BoxPieces.HorizontalTop.ordinal], scale, edgeColorIdx, edgeBgColorIdx)
                }
            }

            for (ay in y + scale until farY step scale) {
                if (join) {
                    //                joinChar(c, x, ay, z.int8, BoxPieces.Vertical, colorIndex)
                    //                joinChar(c, farX, ay, z.int8, BoxPieces.Vertical, colorIndex)
                } else {
                    c.writeScaled(x, ay, z, boxChars[BoxPieces.VerticalLeft.ordinal], scale, edgeColorIdx, edgeBgColorIdx)
                    c.writeScaled(farX, ay, z, boxChars[BoxPieces.VerticalRight.ordinal], scale, edgeColorIdx, edgeBgColorIdx)
                }
            }
        }

        if (join) {
//            joinChar(c, x, y, z, BoxPieces.TopLeft, colorIndex)
//            joinChar(c, farX, y, z, BoxPieces.TopRight, colorIndex)
//            joinChar(c, farX, farY, z, BoxPieces.BottomRight, colorIndex)
//            joinChar(c, x, farY, z, BoxPieces.BottomLeft, colorIndex)
        } else {
            c.writeScaled(x, farY, z, boxChars[BoxPieces.TopLeft.ordinal], scale, edgeColorIdx, edgeBgColorIdx)
            c.writeScaled(farX, farY, z, boxChars[BoxPieces.TopRight.ordinal], scale, edgeColorIdx, edgeBgColorIdx)
            c.writeScaled(farX, y, z, boxChars[BoxPieces.BottomRight.ordinal], scale, edgeColorIdx, edgeBgColorIdx)
            c.writeScaled(x, y, z, boxChars[BoxPieces.BottomLeft.ordinal], scale, edgeColorIdx, edgeBgColorIdx)
        }

        if (fillColor != null && fillColor.a > 0u) {
            for (dx in scale until width - scale) {
                for (dy in scale until height - scale) {
                    c.writeScaled(x + dx, y + dy, z, ' ', 1, clearColorIdx, bgColorIdx)
                }
            }
        }

        c.revision++
    }


    fun drawLine(c: AsciiCanvas, from: Vec3i, to: Vec3i, style: Ascii.BoxStyle, scale: Int, foregroundColor: RGBA, backgroundColor: RGBA?) {
        val boxChars = BoxPieceChars[style.ordinal]

        if (from.x != to.x && from.y != to.y) {
            Noto.err("support for non-horizontal, non-vertical lines not yet written")
        } else {
            val dx = (to.x - from.x).sign
            val dy = (to.y - from.y).sign

            val char = tern(dx == 0, boxChars[BoxPieces.VerticalLeft.ordinal], boxChars[BoxPieces.HorizontalBottom.ordinal])

            var x = from.x
            var y = from.y
            if (dy == 0) {
                while (x != to.x) {
                    c.writeScaled(x, y, from.z, char, scale, foregroundColor, backgroundColor)
                    x += dx
                }
            } else if (dx == 0) {
                while (y != to.y) {
                    c.writeScaled(x, y, from.z, char, scale, foregroundColor, backgroundColor)
                    y += dy
                }
            }
        }
    }
}

/*
proc boxPiecesToConnections(b: BoxPieces): array[4,uint8] =
  case b: # left, right, down, up
    of BoxPieces.TopLeft: [0u8,1u8,1u8,0u8]
    of BoxPieces.TopRight: [1u8,0u8,1u8,0u8]
    of BoxPieces.BottomLeft: [0u8,1u8,0u8,1u8]
    of BoxPieces.BottomRight: [1u8,0u8,0u8,1u8]
    of BoxPieces.Horizontal: [1u8,1u8,0u8,0u8]
    of BoxPieces.Vertical: [0u8,0u8,1u8,1u8]
    of BoxPieces.Cross: [1u8,1u8,1u8,1u8]
    of BoxPieces.RightJoin: [0u8,1u8,1u8,1u8]
    of BoxPieces.LeftJoin: [1u8,0u8,1u8,1u8]
    of BoxPieces.TopJoin: [1u8,1u8,0u8,1u8]
    of BoxPieces.BottomJoin: [1u8,1u8,1u8,0u8]
    of BoxPieces.None: [0u8,0u8,0u8,0u8]
    
proc connectionsToBoxPieces(conn: array[4,uint8]) : BoxPieces =
  # case (conn[0] | (conn[1] << 2) | (conn[2] << 4) | (conn[3] << 8):
  if conn == [0u8,1u8,1u8,0u8]: BoxPieces.TopLeft
  elif conn == [1u8,0u8,1u8,0u8]: BoxPieces.TopRight
  elif conn == [0u8,1u8,0u8,1u8]: BoxPieces.BottomLeft
  elif conn == [1u8,0u8,0u8,1u8]: BoxPieces.BottomRight
  elif conn == [1u8,1u8,0u8,0u8]: BoxPieces.Horizontal
  elif conn == [0u8,0u8,1u8,1u8]: BoxPieces.Vertical
  elif conn == [1u8,1u8,1u8,1u8]: BoxPieces.Cross
  elif conn == [0u8,1u8,1u8,1u8]: BoxPieces.RightJoin
  elif conn == [1u8,0u8,1u8,1u8]: BoxPieces.LeftJoin
  elif conn == [1u8,1u8,0u8,1u8]: BoxPieces.TopJoin
  elif conn == [1u8,1u8,1u8,0u8]: BoxPieces.BottomJoin
  else: BoxPieces.None

proc dimensions*(c: AsciiCanvas) :Vec2i = c.buffer.dimensions

proc joinChar(c : AsciiCanvas, x : int, y : int, z: int8, boxPiece: BoxPieces, colorIndex: uint8) =
  let bounds = effectiveBounds(c)
  if x >= bounds.x and y >= bounds.y and x < bounds.x + bounds.width and y < bounds.y + bounds.height:
    let cur = c.buffer[x,y]
    let info = runeInfo(c, cur.rune)

    let curConn = boxPiecesToConnections(info.boxPiece)
    let addConn = boxPiecesToConnections(boxPiece)
    let effPiece = connectionsToBoxPieces([max(curConn[0],addConn[0]), max(curConn[1],addConn[1]), max(curConn[2],addConn[2]), max(curConn[3],addConn[3])])

    write(c, x, y, Char(rune: BoxPieceChars[effPiece.ordinal], foreground: colorIndex, z: z))


proc drawBox*(c: AsciiCanvas, x,y,z: int, width: int, height: int, color: RGBA, fill: bool, join: bool = true, boxStyle: BoxStyle = BoxStyle.Single) =
  let farX = x + width - 1
  let farY = y + height - 1
  let colorIndex = c.lookupColor(color)

  if join:
    joinChar(c, x, y, z.int8, BoxPieces.TopLeft, colorIndex)
    joinChar(c, farx, y, z.int8, BoxPieces.TopRight, colorIndex)
    joinChar(c, farx, farY, z.int8, BoxPieces.BottomRight, colorIndex)
    joinChar(c, x, farY, z.int8, BoxPieces.BottomLeft, colorIndex)
  else:
    c.write(x,y, Char(rune: BoxPieceChars[BoxPieces.TopLeft.ordinal], foreground: colorIndex, z: z.int8))
    c.write(farX,y, Char(rune: BoxPieceChars[BoxPieces.TopRight.ordinal], foreground: colorIndex, z: z.int8))
    c.write(farx,farY, Char(rune: BoxPieceChars[BoxPieces.BottomRight.ordinal], foreground: colorIndex, z: z.int8))
    c.write(x,farY, Char(rune: BoxPieceChars[BoxPieces.BottomLeft.ordinal], foreground: colorIndex, z: z.int8))

  for ax in x+1 ..< farx:
    if join:
      joinChar(c, ax, y, z.int8, BoxPieces.Horizontal, colorIndex)
      joinChar(c, ax, farY, z.int8, BoxPieces.Horizontal, colorIndex)
    else:
      c.write(ax,y, Char(rune: BoxPieceChars[BoxPieces.Horizontal.ordinal], foreground: colorIndex, z: z.int8))
      c.write(ax,farY, Char(rune: BoxPieceChars[BoxPieces.Horizontal.ordinal], foreground: colorIndex, z: z.int8))

  for ay in y+1 ..< farY:
    if join:
      joinChar(c, x, ay, z.int8, BoxPieces.Vertical, colorIndex)
      joinChar(c, farx, ay, z.int8, BoxPieces.Vertical, colorIndex)
    else:
      c.write(x,ay, Char(rune: BoxPieceChars[BoxPieces.Vertical.ordinal], foreground: colorIndex, z: z.int8))
      c.write(farx,ay, Char(rune: BoxPieceChars[BoxPieces.Vertical.ordinal], foreground: colorIndex, z: z.int8))

  if fill:
    let bw = 1
    for dx in bw ..< width - bw:
      # TODO: actual background colors
      writeColZTested(c, x + dx, y + bw, height - bw * 2, Char(rune: spaceRune, z : z.int8))

  c.revision.inc
 */