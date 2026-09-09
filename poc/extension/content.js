/* Reads visible text only. Never reads document.cookie or storage tokens. */
(function () {
  const KEYS = /weekly usage|extra usage credits|resets|imagine|voice|build/i;
  function visibleText() {
    const root = document.body;
    if (!root) return "";
    return (root.innerText || "").slice(0, 20000);
  }
  const text = visibleText();
  const hit = KEYS.test(text);
  chrome.runtime?.sendMessage?.({
    type: "grok-usage-visible",
    url: location.href,
    hit,
    length: text.length,
  });
  if (hit) {
    console.info("[eslee-poc] grok usage markers present; cookies not read");
  }
})();
