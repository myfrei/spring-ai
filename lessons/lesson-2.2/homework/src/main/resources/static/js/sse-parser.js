export class SseProtocolError extends Error {}

// A bounded parser for our SSE protocol: event/data, comments and CR/LF/CRLF.
export function createSseParser(maxFrameSize = 65_536) {
  let buffer = "";
  let event = "message";
  let data = [];
  let dataSize = 0;

  function line(value, frames) {
    if (value.length > maxFrameSize) throw new SseProtocolError("Слишком большое SSE-событие.");
    if (value === "") {
      if (data.length) frames.push({ event, data: data.join("\n") });
      event = "message"; data = []; dataSize = 0;
      return;
    }
    if (value.startsWith(":")) return;
    const separator = value.indexOf(":");
    const field = separator < 0 ? value : value.slice(0, separator);
    let content = separator < 0 ? "" : value.slice(separator + 1);
    if (content.startsWith(" ")) content = content.slice(1);
    if (field === "event") event = content || "message";
    if (field === "data") {
      dataSize += content.length + 1;
      if (dataSize > maxFrameSize) throw new SseProtocolError("Слишком большое SSE-событие.");
      data.push(content);
    }
  }

  function push(text) {
    buffer += text;
    const frames = [];
    while (true) {
      const position = buffer.search(/[\r\n]/);
      if (position < 0 || (buffer[position] === "\r" && position === buffer.length - 1)) break;
      const size = buffer[position] === "\r" && buffer[position + 1] === "\n" ? 2 : 1;
      line(buffer.slice(0, position), frames);
      buffer = buffer.slice(position + size);
    }
    if (buffer.length > maxFrameSize) throw new SseProtocolError("Слишком большое SSE-событие.");
    return frames;
  }

  function finish() {
    const frames = [];
    if (buffer.endsWith("\r")) {
      line(buffer.slice(0, -1), frames); buffer = "";
    }
    if (buffer || data.length || event !== "message") {
      throw new SseProtocolError("SSE-событие оборвалось до разделителя.");
    }
    return frames;
  }
  return { push, finish };
}
