-- Go Mode's Halo app: shows the text pages and clears that HaloGlasses sends, and reports button presses.
local data = require('data.min')
local text_page = require('text_page')

-- Message codes; HaloGlasses.kt defines the same values.
local TEXT_PAGE = 0x20
local CLEAR = 0x21

-- A single press of the button is sent as [CLICK, SINGLE], as halo-sdk's RxClick reads it. Double
-- and long presses and IMU taps are not reported: Go Mode uses one deliberate gesture.
-- TODO(hardware): Confirm on a Halo that a press reaches HaloDevice.dataResponse as 0B 01. Frame
-- firmware prepends 0x01 to every frame.bluetooth.send (frame-codebase bluetooth.c), which this and
-- Brilliant's own libraries rely on; data.lua's acks carry their own 0x01, which
-- HaloProtocol.DATA_ACK_SUCCESS expects stripped once. On firmware that prepends, acks arrive as
-- 01 00 00, so HaloDevice.sendMessage times out: fix both DATA_ACK values. On firmware that does
-- not, a press arrives as Lua output, which stringResponse reads: send '\x01' before CLICK here.
local CLICK = 0x0B
local SINGLE = 0x01

-- Presses not sent yet: a callback only counts, so a busy radio delays a press instead of losing it.
local presses = 0
frame.button.single(function()
    presses = presses + 1
end)

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
    if presses > 0 and pcall(frame.bluetooth.send, string.char(CLICK, SINGLE)) then
        presses = presses - 1
    end
    frame.sleep(0.01)
end
