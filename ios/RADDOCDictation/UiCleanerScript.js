(function() {
    'use strict';

    var micAllowPolicy = 'microphone; camera; display-capture; clipboard-read; clipboard-write; autoplay; fullscreen';

    function isAppletPage() {
        var h = window.location.href || '';
        return (h.indexOf('/apps/') !== -1 || h.indexOf('fullscreenApplet') !== -1 || h.indexOf('ai.studio') !== -1);
    }

    // 1. Monkey-patch Document.prototype.createElement to grant microphone and download permissions to dynamically created iframes
    if (!window.__raddocIframeHooked) {
        window.__raddocIframeHooked = true;
        try {
            var origCreate = Document.prototype.createElement;
            Document.prototype.createElement = function(tag, opts) {
                var el = origCreate.call(this, tag, opts);
                if (tag && typeof tag === 'string' && tag.toLowerCase() === 'iframe') {
                    try {
                        el.setAttribute('allow', micAllowPolicy);
                        el.allow = micAllowPolicy;
                        var curSb = el.getAttribute('sandbox') || '';
                        if (curSb && curSb.indexOf('allow-downloads') === -1) {
                            el.setAttribute('sandbox', curSb + ' allow-downloads allow-downloads-without-user-activation');
                        }
                    } catch(e) {}
                }
                return el;
            };
        } catch(e) {}
    }

    // 2. Hook HTMLAnchorElement.prototype.click to intercept all Blob and Data downloads for native iOS Share Sheet / Files saving
    if (!window.__raddocAnchorHooked) {
        window.__raddocAnchorHooked = true;
        try {
            var origAnchorClick = HTMLAnchorElement.prototype.click;
            HTMLAnchorElement.prototype.click = function() {
                try {
                    var href = this.href || '';
                    var dl = this.getAttribute('download') || this.download;
                    if (href.indexOf('blob:') === 0 || (dl && href.indexOf('data:') === 0)) {
                        var fname = dl || 'medical_report.docx';
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', href, true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            var reader = new FileReader();
                            reader.onloadend = function() {
                                if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.iosBridge) {
                                    window.webkit.messageHandlers.iosBridge.postMessage({
                                        action: 'saveBase64File',
                                        base64Data: reader.result,
                                        filename: fname,
                                        mimeType: xhr.response.type || 'application/octet-stream'
                                    });
                                }
                            };
                            reader.readAsDataURL(xhr.response);
                        };
                        xhr.send();
                    }
                } catch(e) {
                    console.error('[RADDOC] Download interception error:', e);
                }
                return origAnchorClick.apply(this, arguments);
            };
        } catch(e) {}
    }

    if (window.__raddocCleanInjected) {
        if (typeof window.__raddocEnforce === 'function') window.__raddocEnforce();
        return;
    }
    window.__raddocCleanInjected = true;

    // 3. Intercept postMessage switchToChat error messages originating from the applet iframe
    window.addEventListener('message', function(e) {
        try {
            var origin = (e.origin || '').toLowerCase();
            if (origin.indexOf('accounts.google') !== -1 || origin.indexOf('apis.google') !== -1) return;
            var d = e.data;
            if (!d) return;
            var isErr = false;
            if (typeof d === 'string') {
                var s = d.toLowerCase();
                if (s.indexOf('switchtochat') !== -1 || s.indexOf('view_chat') !== -1 || s.indexOf('switch_to_chat') !== -1) {
                    isErr = true;
                }
            } else if (typeof d === 'object') {
                var action = String(d.action || d.type || d.event || '').toLowerCase();
                if (action === 'switchtochat' || action === 'view_chat' || action === 'switch_tab_chat') {
                    isErr = true;
                }
            }
            if (isErr) {
                e.stopImmediatePropagation();
                e.stopPropagation();
            }
        } catch(ex) {}
    }, true);

    // 4. Main enforcement function
    window.__raddocEnforce = function() {
        try {
            if (!isAppletPage()) return;

            // A. Pin dictation applet iframe to fullscreen at high z-index and ensure microphone permissions
            var iframes = document.querySelectorAll('iframe');
            var appletIframe = null;
            for (var i = 0; i < iframes.length; i++) {
                var ifr = iframes[i];
                try {
                    var curSb = ifr.getAttribute('sandbox') || '';
                    if (curSb && curSb.indexOf('allow-downloads') === -1) {
                        ifr.setAttribute('sandbox', curSb + ' allow-downloads allow-downloads-without-user-activation');
                    }
                    var curAllow = ifr.getAttribute('allow') || '';
                    if (curAllow.indexOf('microphone') === -1) {
                        ifr.setAttribute('allow', micAllowPolicy);
                        ifr.allow = micAllowPolicy;
                        if (!ifr.hasAttribute('data-raddoc-mic-set')) {
                            ifr.setAttribute('data-raddoc-mic-set', 'true');
                            if (ifr.src && ifr.src.indexOf('about:blank') === -1) {
                                ifr.src = ifr.src;
                            }
                        }
                    }
                } catch(e) {}
                var src = (ifr.src || '').toLowerCase();
                if (src.indexOf('accounts.google') !== -1) continue;
                if (src.indexOf('usercontent') !== -1 || ifr.hasAttribute('sandbox') || (ifr.offsetWidth > 100 && ifr.offsetHeight > 100)) {
                    appletIframe = ifr;
                    break;
                }
            }

            if (appletIframe) {
                try {
                    appletIframe.setAttribute('allow', micAllowPolicy);
                    appletIframe.allow = micAllowPolicy;
                } catch(e) {}
                appletIframe.style.setProperty('position', 'fixed', 'important');
                appletIframe.style.setProperty('top', '0px', 'important');
                appletIframe.style.setProperty('left', '0px', 'important');
                appletIframe.style.setProperty('width', '100vw', 'important');
                appletIframe.style.setProperty('height', '100vh', 'important');
                appletIframe.style.setProperty('max-height', '100vh', 'important');
                appletIframe.style.setProperty('z-index', '99999', 'important');
                appletIframe.style.setProperty('border', 'none', 'important');
                appletIframe.style.setProperty('margin', '0px', 'important');
            }

            // B. Detect if Chat view is active
            var bodyText = document.body ? (document.body.innerText || '') : '';
            var isChatShowing = (bodyText.indexOf('Remix to make this app your own') !== -1 || 
                                 bodyText.indexOf('Here are some ideas to try') !== -1 || 
                                 bodyText.indexOf('Generate video from text') !== -1);

            // C. Locate Preview tab button
            var buttons = document.querySelectorAll('button, [role="tab"], [role="button"], a');
            var previewBtn = null;
            for (var b = 0; b < buttons.length; b++) {
                var btn = buttons[b];
                var txt = (btn.textContent || '').trim();
                var aria = btn.getAttribute('aria-label') || '';
                if (txt === 'Preview' || aria === 'Preview') {
                    previewBtn = btn;
                    var isSelected = btn.getAttribute('aria-selected') === 'true' || 
                                     btn.classList.contains('active') || 
                                     btn.classList.contains('selected') || 
                                     btn.classList.contains('mdc-tab--active');
                    if (!isSelected) {
                        isChatShowing = true;
                    }
                    break;
                }
            }

            // If Chat view is displayed or Preview is deselected, click Preview immediately!
            if (previewBtn && isChatShowing) {
                previewBtn.click();
            }

            // D. Safely hide disclaimer banner
            var allElements = document.querySelectorAll('p, span, footer, aside, [role="status"], [role="alert"]');
            for (var j = 0; j < allElements.length; j++) {
                var el = allElements[j];
                var t = el.textContent || '';
                if (t.indexOf('This app was developed by another user') !== -1 && el.children.length === 0) {
                    var parent = el.parentElement;
                    if (parent && parent !== document.body && !parent.querySelector('iframe')) {
                        parent.style.setProperty('display', 'none', 'important');
                    }
                }
            }

            // E. Silent Web Audio Keep-Alive
            if (!window.__raddocSilentAudio) {
                try {
                    var audioCtx = new (window.AudioContext || window.webkitAudioContext)();
                    var buffer = audioCtx.createBuffer(1, audioCtx.sampleRate * 2, audioCtx.sampleRate);
                    var source = audioCtx.createBufferSource();
                    source.buffer = buffer;
                    source.loop = true;
                    var gainNode = audioCtx.createGain();
                    gainNode.gain.value = 0.0001;
                    source.connect(gainNode);
                    gainNode.connect(audioCtx.destination);
                    source.start(0);
                    window.__raddocSilentAudio = { ctx: audioCtx, src: source };
                } catch(eAudio) {
                    try {
                        var a = document.createElement('audio');
                        a.src = 'data:audio/wav;base64,UklGRigAAABXQVZFZm10IBIAAAABAAEARKwAAIhYAQACABAAAABkYXRhAgAAAAEA';
                        a.loop = true;
                        a.volume = 0.01;
                        a.play().catch(function(){});
                        window.__raddocSilentAudio = a;
                    } catch(eAudio2) {}
                }
            }
            if (window.__raddocSilentAudio && window.__raddocSilentAudio.ctx && window.__raddocSilentAudio.ctx.state === 'suspended') {
                window.__raddocSilentAudio.ctx.resume().catch(function(){});
            }
        } catch(e) {}
    };

    window.__raddocEnforce();
    if (!window.__raddocInterval) {
        window.__raddocInterval = setInterval(window.__raddocEnforce, 300);
    }
})();
