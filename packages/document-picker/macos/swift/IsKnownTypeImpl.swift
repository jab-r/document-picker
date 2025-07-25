//
//  IsKnownTypeImpl.swift
//  react-native-document-picker
//
//  Created by Jonathan Borden based on Vojtech Novak on 26.05.2024.
// LICENSE: see License.md in the package root
//

import Foundation
import UniformTypeIdentifiers

@objc public class IsKnownTypeImpl: NSObject {
  
  @objc public static func isKnownType(_ type: String) -> Bool {
    if let utType = UTType(type) {
      return utType.isDeclared
    }
    return false
  }
  
  @objc public static func checkType(_ kind: String, value: String) -> NSDictionary {
    let dict = getTypeResult(kind, value: value)
    return NSDictionary(dictionary: dict as [AnyHashable: Any])
  }

  static func getTypeResult(_ kind: String, value: String) -> Dictionary<String, Any?> {
    if let utType = createUTType(kind: kind, value: value), utType.isDeclared == true {
      return ["isKnown": true,
              "UTType": utType.identifier,
              "preferredFilenameExtension": utType.preferredFilenameExtension,
              "mimeType": utType.preferredMIMEType]
    }
    return ["isKnown": false, "UTType": nil, "preferredFilenameExtension": nil, "mimeType": nil]
  }

  static func createUTType(kind: String, value: String) -> UTType? {
    switch kind {
    case "UTType": UTType(value)
    case "mimeType": UTType(mimeType: value)
    case "extension": UTType(filenameExtension: value)
    default: nil
    }
  }
}