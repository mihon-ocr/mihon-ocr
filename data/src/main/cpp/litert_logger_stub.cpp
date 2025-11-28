// Copyright 2024 Google LLC.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Stub implementations for LiteRT logger functions.
// These are needed because the actual implementations are not exported from
// libLiteRt.so, but the LiteRT C++ wrapper macros use them.

#include "litert/c/internal/litert_logging.h"

#include <cstdarg>
#include <cstdio>

// Simple default logger implementation - a dummy logger struct
struct LiteRtLoggerT {
  LiteRtLogSeverity min_severity = kLiteRtLogSeverityInfo;
};

// Global default logger instance
static LiteRtLoggerT g_default_logger;

extern "C" {

const char* LiteRtGetLogSeverityName(LiteRtLogSeverity severity) {
  switch (severity) {
    case kLiteRtLogSeverityDebug:
      return "DEBUG";
    case kLiteRtLogSeverityVerbose:
      return "VERBOSE";
    case kLiteRtLogSeverityInfo:
      return "INFO";
    case kLiteRtLogSeverityWarning:
      return "WARNING";
    case kLiteRtLogSeverityError:
      return "ERROR";
    case kLiteRtLogSeveritySilent:
      return "SILENT";
    default:
      return "UNKNOWN";
  }
}

LiteRtLogger LiteRtGetDefaultLogger() {
  return &g_default_logger;
}

LiteRtStatus LiteRtSetDefaultLogger(LiteRtLogger logger) {
  // No-op for stub
  return kLiteRtStatusOk;
}

LiteRtStatus LiteRtCreateLogger(LiteRtLogger* logger) {
  if (!logger) return kLiteRtStatusErrorInvalidArgument;
  *logger = new LiteRtLoggerT();
  return kLiteRtStatusOk;
}

void LiteRtDestroyLogger(LiteRtLogger logger) {
  if (logger && logger != &g_default_logger) {
    delete logger;
  }
}

LiteRtStatus LiteRtGetMinLoggerSeverity(LiteRtLogger logger,
                                        LiteRtLogSeverity* min_severity) {
  if (!logger || !min_severity) return kLiteRtStatusErrorInvalidArgument;
  *min_severity = logger->min_severity;
  return kLiteRtStatusOk;
}

LiteRtStatus LiteRtSetMinLoggerSeverity(LiteRtLogger logger,
                                        LiteRtLogSeverity min_severity) {
  if (!logger) return kLiteRtStatusErrorInvalidArgument;
  logger->min_severity = min_severity;
  return kLiteRtStatusOk;
}

LiteRtStatus LiteRtLoggerLog(LiteRtLogger logger, LiteRtLogSeverity severity,
                             const char* format, ...) {
  if (!logger) return kLiteRtStatusErrorInvalidArgument;
  
  // Only log if severity >= min_severity
  if (severity < logger->min_severity) {
    return kLiteRtStatusOk;
  }
  
  // Output to stderr with severity prefix
  fprintf(stderr, "[LiteRT %s] ", LiteRtGetLogSeverityName(severity));
  
  va_list args;
  va_start(args, format);
  vfprintf(stderr, format, args);
  va_end(args);
  
  fprintf(stderr, "\n");
  return kLiteRtStatusOk;
}

LiteRtStatus LiteRtDefaultLoggerLog(LiteRtLogSeverity severity,
                                    const char* format, ...) {
  LiteRtLogger logger = LiteRtGetDefaultLogger();
  if (!logger) return kLiteRtStatusErrorInvalidArgument;
  
  // Only log if severity >= min_severity
  if (severity < logger->min_severity) {
    return kLiteRtStatusOk;
  }
  
  // Output to stderr with severity prefix
  fprintf(stderr, "[LiteRT %s] ", LiteRtGetLogSeverityName(severity));
  
  va_list args;
  va_start(args, format);
  vfprintf(stderr, format, args);
  va_end(args);
  
  fprintf(stderr, "\n");
  return kLiteRtStatusOk;
}

LiteRtStatus LiteRtGetLoggerIdentifier(LiteRtLoggerConst logger,
                                       const char** identifier) {
  if (!logger || !identifier) return kLiteRtStatusErrorInvalidArgument;
  *identifier = "default";
  return kLiteRtStatusOk;
}

LiteRtStatus LiteRtCreateSinkLogger(LiteRtLogger* logger) {
  if (!logger) return kLiteRtStatusErrorInvalidArgument;
  *logger = new LiteRtLoggerT();
  return kLiteRtStatusOk;
}

LiteRtStatus LiteRtGetSinkLoggerSize(LiteRtLogger logger, size_t* size) {
  if (!logger || !size) return kLiteRtStatusErrorInvalidArgument;
  *size = 0;  // Stub - no messages stored
  return kLiteRtStatusOk;
}

LiteRtStatus LiteRtGetSinkLoggerMessage(LiteRtLogger logger, size_t idx,
                                        const char** message) {
  if (!logger || !message) return kLiteRtStatusErrorInvalidArgument;
  *message = "";  // Stub - no messages stored
  return kLiteRtStatusOk;
}

LiteRtStatus LiteRtClearSinkLogger(LiteRtLogger logger) {
  if (!logger) return kLiteRtStatusErrorInvalidArgument;
  return kLiteRtStatusOk;
}

LiteRtStatus LiteRtUseStandardLogger() {
  return kLiteRtStatusOk;
}

}  // extern "C"
