-- Draws pages of sprites: a header message (TextPageData.pack or TxImagePage.pack) followed by one
-- TxSprite message per text line or image strip, in order, all under the same message code.
local sprite = require('sprite.min')

local _M = {}

-- Adds one message to page, the page being received or nil. Returns the page and whether this
-- message completed it. A header always starts a new page; a sprite without a page is dropped.
function _M.parse_text_page(data, page)
    if string.byte(data, 1) == 0xFF then
        local new = { count = string.byte(data, 6), received = 0, lines = {} }
        for i = 1, new.count do
            local o = 7 + (i - 1) * 4
            new.lines[i] = {
                x = string.byte(data, o) << 8 | string.byte(data, o + 1),
                y = string.byte(data, o + 2) << 8 | string.byte(data, o + 3),
            }
        end
        return new, new.count == 0
    end
    if page == nil or page.received >= page.count then
        return page, false
    end
    page.received = page.received + 1
    page.lines[page.received].sprite = sprite.parse_sprite(data)
    return page, page.received == page.count
end

-- Replaces the display with a complete page. Each 1, 2, or 4 bpp sprite draws through its own
-- palette, as Brilliant's Halo image view passes it, so the display's palette never changes.
-- TODO(hardware): Confirm on a Halo that palette_data colours a sprite. Evidence so far: brilliant_sdk
-- b644147 passes it on Halo in flutter/packages/simple_brilliant_app/example/layout/assets/lua/src/
-- view/image_view.lua line 39, and its halo_emulator honours it. If Halo ignores it, text draws in
-- the default palette's index 1 (white) as before, and images need sprite.set_palette instead.
function _M.draw(page)
    frame.display.clear()
    for i = 1, page.count do
        local line = page.lines[i]
        local s = line.sprite
        frame.display.bitmap(line.x + 1, line.y + 1, s.width, 1 << s.bpp, 0, s.pixel_data,
            { palette_data = s.palette_data })
    end
    frame.display.show()
end

return _M
