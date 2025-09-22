// 百度主动提交
(function () {
    var bp = document.createElement('script');
    var curProtocol = window.location.protocol.split(':')[0];
    if (curProtocol === 'https') {
        bp.src = 'https://zz.bdstatic.com/linksubmit/push.js';
    } else {
        bp.src = 'http://push.zhanzhang.baidu.com/push.js';
    }
    var s = document.getElementsByTagName("script")[0];
    s.parentNode.insertBefore(bp, s);
})();

// 谷歌统计
function addScript() {
    document.write('<script async src="https://www.googletagmanager.com/gtag/js?id=G-YB8JLJG96M"><\/script>');
}
addScript();

window.dataLayer = window.dataLayer || [];
function gtag() {
    dataLayer.push(arguments);
}
gtag('js', new Date());
gtag('config', 'G-YB8JLJG96M');
