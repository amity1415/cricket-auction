/* Export a DOM node (a team card) to a downloadable PNG — no external libraries.
 *
 * Technique: deep-clone the node, inline every element's *computed* style (so all
 * CSS variables and classes resolve to concrete values), convert each same-origin
 * <img> to a data: URL (so the canvas is never tainted), then rasterise the clone
 * through an SVG <foreignObject> and save the canvas as a PNG. Everything the
 * showcase uses is same-origin (player photos, team crests, the sponsor logo) and
 * the fonts are system fonts, so the result matches the on-screen card. */
(function (global) {
  'use strict';

  const BACKDROP = '#0a1130';   // fills the card's rounded-corner transparency

  function inlineComputedStyles(src, dst) {
    const cs = getComputedStyle(src);
    let css = '';
    for (let i = 0; i < cs.length; i++) {
      const prop = cs[i];
      css += prop + ':' + cs.getPropertyValue(prop) + ';';
    }
    dst.style.cssText = css;
    const s = src.children, d = dst.children;
    const n = Math.min(s.length, d.length);
    for (let i = 0; i < n; i++) inlineComputedStyles(s[i], d[i]);
  }

  function imgToDataURL(img) {
    try {
      const w = img.naturalWidth, h = img.naturalHeight;
      if (!w || !h) return null;
      const c = document.createElement('canvas');
      c.width = w; c.height = h;
      c.getContext('2d').drawImage(img, 0, 0);
      return c.toDataURL('image/png');
    } catch (e) { return null; }
  }

  async function inlineImages(cloneRoot, srcRoot) {
    const clones = cloneRoot.querySelectorAll('img');
    const origs = srcRoot.querySelectorAll('img');
    for (let i = 0; i < clones.length; i++) {
      const orig = origs[i];
      if (orig && !orig.complete) {
        await new Promise(r => { orig.onload = orig.onerror = r; });
      }
      const data = orig ? imgToDataURL(orig) : null;
      if (data) clones[i].setAttribute('src', data);
      else clones[i].remove();   // photo missing/failed → drop so initials show
    }
  }

  // Wait until every image in the node has finished loading (or failed) so the
  // DOM is stable before we clone it — some avatars remove themselves on error.
  async function settleImages(node) {
    await Promise.all([...node.querySelectorAll('img')].map(img =>
      img.complete ? Promise.resolve() : new Promise(res => {
        img.addEventListener('load', res, { once: true });
        img.addEventListener('error', res, { once: true });
      })));
  }

  async function toPng(node, scale) {
    scale = scale || 2;
    await settleImages(node);
    const rect = node.getBoundingClientRect();
    const w = Math.ceil(rect.width), h = Math.ceil(rect.height);

    const clone = node.cloneNode(true);
    inlineComputedStyles(node, clone);      // structures still identical here
    await inlineImages(clone, node);        // (imgs align 1:1 with the original)
    clone.querySelectorAll('.tc-shot, .tc-save, .no-export').forEach(e => e.remove());
    clone.style.margin = '0';
    clone.style.transform = 'none';

    const xml = new XMLSerializer().serializeToString(clone);
    const svg =
      '<svg xmlns="http://www.w3.org/2000/svg" width="' + w + '" height="' + h + '">' +
      '<foreignObject x="0" y="0" width="' + w + '" height="' + h + '">' +
      '<div xmlns="http://www.w3.org/1999/xhtml" style="width:' + w + 'px;height:' + h + 'px">' +
      xml + '</div></foreignObject></svg>';
    const url = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);

    const image = new Image();
    image.width = w; image.height = h;
    await new Promise((res, rej) => { image.onload = res; image.onerror = rej; image.src = url; });

    const canvas = document.createElement('canvas');
    canvas.width = w * scale; canvas.height = h * scale;
    const ctx = canvas.getContext('2d');
    ctx.setTransform(scale, 0, 0, scale, 0, 0);
    ctx.fillStyle = BACKDROP;
    ctx.fillRect(0, 0, w, h);
    ctx.drawImage(image, 0, 0);
    return canvas.toDataURL('image/png');
  }

  async function downloadPng(node, filename) {
    const dataUrl = await toPng(node, 2);
    const a = document.createElement('a');
    a.href = dataUrl;
    a.download = filename || 'card.png';
    document.body.appendChild(a);
    a.click();
    a.remove();
  }

  global.CardExport = { toPng, downloadPng };
})(window);
