-- Go Mode's Halo app: shows the text pages and clears that HaloGlasses sends.
local data = require('data.min')
local text_page = require('text_page')

-- Message codes; HaloGlasses.kt defines the same values.
local TEXT_PAGE = 0x20
local CLEAR = 0x21

frame.display.clear()
frame.display.show()
local page = nil
while true do
    for _, item in ipairs(data.process_raw_items()) do
        local code, payload = item[1], item[2]
        if code == TEXT_PAGE then
            local complete
            page, complete = text_page.parse_text_page(payload, page)
            if complete then
                text_page.draw(page)
            end
        elseif code == CLEAR then
            page = nil
            frame.display.clear()
            frame.display.show()
        end
    end
    frame.sleep(0.01)
end
