// Defines the display extension through which a Go Mode client declares its connected glasses to MCP servers.

package gomode

import (
	"errors"
	"fmt"
)

// DisplayExtension identifies the client capability extension that declares
// the display of the glasses connected to a Go Mode client.
//
// A Go Mode client puts the [Display] settings object under this key in
// _meta["io.modelcontextprotocol/clientCapabilities"].extensions of every MCP
// request while glasses are connected, and omits the key otherwise. MCP
// declares client capabilities per request, so connecting or disconnecting
// glasses takes effect on the next request and a server must not carry a
// display over from an earlier request. The web client never sends it.
//
// A server that fits its content to the display falls back to its display-less
// output when the key is absent or its value is malformed, including a
// [DisplayShape] or [DisplayColorSpace] value it does not know. Adding an
// optional field or an enumeration value is not breaking; any other change
// to the settings object takes the identifier "com.fghbuild.gomode/display-v2".
//
// Example request metadata with a Halo connected:
//
//	"_meta": {
//	  "io.modelcontextprotocol/clientCapabilities": {
//	    "extensions": {
//	      "com.fghbuild.gomode/display": {
//	        "width": 256, "height": 256, "shape": "circle",
//	        "colorSpace": "rgb", "bitsPerPixel": 4
//	      }
//	    }
//	  }
//	}
const DisplayExtension = "com.fghbuild.gomode/display"

// ClientExtensions holds the settings objects of the Go Mode client capability
// extensions, keyed as in ClientCapabilities.extensions. Clients encode it to
// build that map, so generated SDKs carry each extension identifier.
type ClientExtensions struct {
	// Display is present while glasses are connected.
	Display *Display `json:"com.fghbuild.gomode/display,omitempty"`
}

// Display describes one connected display so a server can fit its content to
// it. It is the settings object of the com.fghbuild.gomode/display client
// capability extension, whose contract the Go constant DisplayExtension
// documents. Servers must ignore fields they do not know.
type Display struct {
	// Width is the number of pixel columns. It is positive.
	Width int `json:"width"`
	// Height is the number of pixel rows. It is positive.
	Height int `json:"height"`
	// Shape is the visible area within the Width by Height pixels.
	Shape DisplayShape `json:"shape"`
	// ColorSpace is what a pixel value represents.
	ColorSpace DisplayColorSpace `json:"colorSpace"`
	// BitsPerPixel is the pixel depth, from 1 to 24: a pixel shows one of
	// 2^BitsPerPixel luminance levels or colours, as ColorSpace defines.
	BitsPerPixel int `json:"bitsPerPixel"`
}

// Validate returns an error if d is not a well-formed display description.
// It rejects shape and colour space values this version does not define.
func (d *Display) Validate() error {
	var errs []error
	if d.Width <= 0 {
		errs = append(errs, fmt.Errorf("width %d must be positive", d.Width))
	}
	if d.Height <= 0 {
		errs = append(errs, fmt.Errorf("height %d must be positive", d.Height))
	}
	switch d.Shape {
	case DisplayShapeCircle, DisplayShapeRectangle:
	default:
		errs = append(errs, fmt.Errorf("unknown shape %q", d.Shape))
	}
	switch d.ColorSpace {
	case DisplayColorSpaceLuminance, DisplayColorSpaceRGB:
	default:
		errs = append(errs, fmt.Errorf("unknown colorSpace %q", d.ColorSpace))
	}
	if d.BitsPerPixel < 1 || d.BitsPerPixel > 24 {
		errs = append(errs, fmt.Errorf("bitsPerPixel %d must be from 1 to 24", d.BitsPerPixel))
	}
	return errors.Join(errs...)
}

// DisplayShape is the visible area of a display.
type DisplayShape string

// Display shapes.
const (
	// DisplayShapeCircle shows the circle inscribed in the Width by Height
	// pixels; pixels outside it are not visible.
	DisplayShapeCircle DisplayShape = "circle"
	// DisplayShapeRectangle shows every pixel.
	DisplayShapeRectangle DisplayShape = "rectangle"
)

// DisplayColorSpace is what a display's pixel values represent.
type DisplayColorSpace string

// Display colour spaces.
const (
	// DisplayColorSpaceLuminance pixels are brightness levels that the device
	// shows in its single tint, such as green on Even Realities G2.
	DisplayColorSpaceLuminance DisplayColorSpace = "luminance"
	// DisplayColorSpaceRGB pixels are colours. Below 24 bits per pixel, the
	// client picks the 2^BitsPerPixel colours of each image from 24-bit RGB.
	DisplayColorSpaceRGB DisplayColorSpace = "rgb"
)
