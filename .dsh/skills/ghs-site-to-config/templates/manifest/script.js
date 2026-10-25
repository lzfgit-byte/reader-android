// manifest 形态的脚本：只写方法，meta / render / behavior 在 config.json 里。
// 引擎执行后把方法挂到配置对象上，所以 this.homeUrl / this.searchUrl 可用。
/* break */ ({
  getItems($) {
    const items = [];
    $('.item').each(function (index, element) {           // TODO 条目容器
      const $item = $(element);
      const href = helpElAttr($item.find(ElementTypes.a), ElementAttr.href);
      // 详情链接要能补全：javascript: / # 这类跳不出页面，直接跳过
      if (!href || href.indexOf('javascript:') === 0 || href === '#') return;
      items.push({
        jumpUrl: href,
        title: helpElText($item.find('.title')),           // TODO 标题
        coverImg: helpElAttr($item.find(ElementTypes.img), ElementAttr.dataSrc)
          || helpElAttr($item.find(ElementTypes.img), ElementAttr.src),
        type: 'image',
        renderType: 'normal',
        tags: [],
      });
    });
    return items;
  },

  getPagination($) {
    const pages = [];
    $('.pager a').each(function (index, element) {         // TODO 分页容器
      const $a = $(element);
      pages.push({
        title: helpElText($a),
        url: helpElAttr($a, ElementAttr.href),
        isCurrent: $a.hasClass('active'),
      });
    });
    return pages;
  },

  getTags($) {
    return [{ title: '全部', url: this.homeUrl }];
  },

  getUrlReplace($) {
    return [];
  },

  async getDetailInfo(item, cheerio) {
    const html = await getHtml(item.jumpUrl);
    const $page = cheerio.load(html);
    const details = [];
    $page('.gallery img').each(function (index, element) { // TODO 详情页图片
      const $img = $(element);
      details.push({
        type: 'image',
        url: helpElAttr($img, ElementAttr.src),
        fullUrl: helpElAttr($img, ElementAttr.dataOriginal),
        title: helpElAttr($img, ElementAttr.alt),
      });
    });
    if (details.length === 0) return null;
    return { detailType: 'image', renderType: 'normal', details: details, relations: [] };
  },

  adapterLoadUrl(url) {
    return url;
  },

  adapterSearchUrl(keyword) {
    return this.homeUrl + 'search?q=' + encodeURIComponent(keyword);
  },
});
