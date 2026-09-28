import type {CameraPreviewStartParams} from "../BluetoothSdk.types"

const OPTIONAL_NUMBER_KEYS = [
  "intervalMs",
  "width",
  "height",
  "quality",
  "bitrateKbps",
  "keyframeIntervalMs",
] as const

/**
 * Omitted optional fields stay omitted so the glasses apply their own defaults; the glasses also
 * clamp, so values pass through unclamped. Expo Android bridge rejects null values in Map<String, Any>.
 * `format` passes through as given; the glasses answer an unknown one with `unsupported_format`.
 */
export function cameraPreviewParamsForNative(params: CameraPreviewStartParams): Record<string, string | number> {
  const payload: Record<string, string | number> = {url: params.url, token: params.token}
  if (typeof params.format === "string" && params.format.length > 0) {
    payload.format = params.format
  }
  for (const key of OPTIONAL_NUMBER_KEYS) {
    const value = params[key]
    if (value != null && Number.isFinite(value)) {
      payload[key] = Math.round(value)
    }
  }
  return payload
}
