const {cameraPreviewParamsForNative} = require("../_private/cameraPreviewPayload")

const baseParams = {
  url: "http://192.168.1.42:8787/preview",
  token: "secret-token",
}

describe("cameraPreviewParamsForNative", () => {
  it("sends only url and token when optional fields are omitted so glasses use their defaults", () => {
    expect(cameraPreviewParamsForNative(baseParams)).toEqual(baseParams)
  })

  it("omits nullish and non-finite optional fields", () => {
    const payload = cameraPreviewParamsForNative({
      ...baseParams,
      intervalMs: undefined,
      width: null,
      height: Number.NaN,
      quality: Number.POSITIVE_INFINITY,
    })
    expect(payload).toEqual(baseParams)
  })

  it("passes explicit values through as integers without clamping", () => {
    const payload = cameraPreviewParamsForNative({
      ...baseParams,
      intervalMs: 20.4,
      width: 640,
      height: 480,
      quality: 99.6,
    })
    expect(payload).toEqual({...baseParams, intervalMs: 20, width: 640, height: 480, quality: 100})
  })

  it("passes the H.264 format, bitrate and keyframe interval through", () => {
    const payload = cameraPreviewParamsForNative({
      ...baseParams,
      format: "h264",
      bitrateKbps: 1500.4,
      keyframeIntervalMs: 1000,
    })
    expect(payload).toEqual({
      ...baseParams,
      format: "h264",
      bitrateKbps: 1500,
      keyframeIntervalMs: 1000,
    })
  })

  it("leaves out a missing, null or empty format so the glasses default to JPEG", () => {
    expect(
      cameraPreviewParamsForNative({...baseParams, format: null, bitrateKbps: undefined}),
    ).toEqual(baseParams)
    expect(cameraPreviewParamsForNative({...baseParams, format: ""})).toEqual(baseParams)
  })
})
