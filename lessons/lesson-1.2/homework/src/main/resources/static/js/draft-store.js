export function createDraftStore(storage, key = "repopilot.draft.v1") {
  return {
    read() { // TODO HW-2: JSON v1, строка до 4000, ошибки storage не ломают чат.
      throw new Error("TODO HW-2: read draft");
    },
    save(text) { // TODO HW-2: сохранить валидный текст; true при успехе.
      throw new Error("TODO HW-2: save draft");
    },
    clearIfUnchanged(expected) { // TODO HW-2: не удалять более новый черновик.
      throw new Error("TODO HW-2: compare and clear");
    }
  };
}
