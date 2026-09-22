//
//  ObservableStore.swift
//  BluetoothSdk
//
//  Observable state management with immediate event emission
//

import Foundation

@MainActor
class ObservableStore {
    private let lock = NSLock()
    private var values: [String: Any] = [:]
    private var onEmit: ((String, [String: Any]) -> Void)?
    private var listeners: [String: (String, [String: Any]) -> Void] = [:]

    nonisolated static let bluetoothCategory = "bluetooth"
    private nonisolated static let legacyCoreCategory = "core"

    nonisolated static func normalizeCategory(_ category: String) -> String {
        category == legacyCoreCategory ? bluetoothCategory : category
    }

    func configure(onEmit: @escaping (String, [String: Any]) -> Void) {
        lock.lock()
        self.onEmit = onEmit
        lock.unlock()
    }

    func addListener(_ listener: @escaping (String, [String: Any]) -> Void) -> String {
        let id = UUID().uuidString
        lock.lock()
        listeners[id] = listener
        lock.unlock()
        return id
    }

    func removeListener(_ id: String) {
        lock.lock()
        listeners.removeValue(forKey: id)
        lock.unlock()
    }

    func set(_ category: String, _ key: String, _ value: Any) {
        let normalizedCategory = Self.normalizeCategory(category)
        let fullKey = "\(normalizedCategory).\(key)"

        lock.lock()
        let oldValue = values[fullKey]

        // Skip if unchanged
        if let old = oldValue, areEqual(old, value) {
            lock.unlock()
            return
        }

        values[fullKey] = value
        let emit = onEmit
        let listenersSnapshot = Array(listeners.values)
        lock.unlock()

        // Emit immediately (outside the lock so listeners can re-enter the store)
        let changes = [key: value]
        emit?(normalizedCategory, changes)
        for listener in listenersSnapshot {
            listener(normalizedCategory, changes)
        }
    }

    func remove(_ category: String, _ key: String) {
        let normalizedCategory = Self.normalizeCategory(category)
        let fullKey = "\(normalizedCategory).\(key)"
        lock.lock()
        guard values[fullKey] != nil else {
            lock.unlock()
            return
        }
        values.removeValue(forKey: fullKey)
        let emit = onEmit
        let listenersSnapshot = Array(listeners.values)
        lock.unlock()
        // Emit updated category snapshot so UI listeners clear the removed key
        let snapshot = getCategory(normalizedCategory)
        emit?(normalizedCategory, snapshot)
        for listener in listenersSnapshot { listener(normalizedCategory, snapshot) }
    }

    func get(_ category: String, _ key: String) -> Any? {
        lock.lock()
        defer { lock.unlock() }
        return values["\(Self.normalizeCategory(category)).\(key)"]
    }

    func wouldSkipSet(_ category: String, _ key: String, _ value: Any) -> Bool {
        let fullKey = "\(Self.normalizeCategory(category)).\(key)"
        lock.lock()
        defer { lock.unlock() }
        guard let oldValue = values[fullKey] else { return false }
        return areEqual(oldValue, value)
    }

    func getCategory(_ category: String) -> [String: Any] {
        var result: [String: Any] = [:]
        let prefix = "\(Self.normalizeCategory(category))."
        lock.lock()
        defer { lock.unlock() }
        for (key, value) in values where key.hasPrefix(prefix) {
            let shortKey = String(key.dropFirst(prefix.count))
            result[shortKey] = value
        }
        return result
    }

    /// Helper to compare values
    private func areEqual(_ lhs: Any, _ rhs: Any) -> Bool {
        if let l = lhs as? String, let r = rhs as? String { return l == r }
        if let l = lhs as? Int, let r = rhs as? Int { return l == r }
        if let l = lhs as? Bool, let r = rhs as? Bool { return l == r }
        if let l = lhs as? Double, let r = rhs as? Double { return l == r }
        if let l = lhs as? [String], let r = rhs as? [String] { return l == r }
        if let l = lhs as? [[String: Any]], let r = rhs as? [[String: Any]] {
            return toJson(l) == toJson(r)
        }
        return false
    }

    private func toJson(_ value: Any) -> String? {
        guard let data = try? JSONSerialization.data(withJSONObject: value) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
