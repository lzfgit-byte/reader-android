// inline 形态模板：整份配置是一个「返回对象的表达式」。
// 用法：复制到工作区改名，先把 TODO 换成实测选择器，再逐个方法验证。
/* break */ ({
  key: 'example-site',
  name: '示例站点',
  version: '1.0.0',
  homeUrl: 'https://example.com/list',
  favicon: '',
  render: {
    imgWidth: '180px',
    imgHeight: '240px',
    cardStyle: 'cover-title',
    readerMode: 'auto',
    setTags: ['标签', '收藏', '历史'],
  },
  behavior: {
    requestTimeoutMs: 20000,
    useWebView: false,
  },

  // ---- 列表：一条 = 一张卡片 --------------------------------------------
  getItems($) {
    const items = [];
    $('.item').each(function (index, element) {          // TODO 条目容器
      const $item = $(element);
      const href = helpElAttr($item.find(ElementTypes.a), ElementAttr.href);
      // 详情链接要能补全：javascript: / #  这类跳不出页面，直接跳过
      if (!href || href.indexOf('javascript:') === 0 || href === '#') return;
      items.push({
        jumpUrl: href,
        title: helpElText($item.find('.title')),          // TODO 标题
        coverImg: helpElAttr($item.find(ElementTypes.img), ElementAttr.dataSrc)
          || helpElAttr($item.find(ElementTypes.img), ElementAttr.src),
        type: 'image',                                   // image | comic | video | text
        renderType: 'normal',
        tags: [],
      });
    });
    return items;
  },

  // ---- 分页：当前页 isCurrent，禁用项 url 给空串 -------------------------
  getPagination($) {
    const pages = [];
    $('.pager a').each(function (index, element) {        // TODO 分页容器
      const $a = $(element);
      pages.push({
        title: helpElText($a),
        url: helpElAttr($a, ElementAttr.href),
        isCurrent: $a.hasClass('active'),
      });
    });
    return pages;
  },

  // ---- 标签云：url 为空串 = 占位/分隔，不可点 ----------------------------
  getTags($) {
    return [{ title: '全部', url: this.homeUrl }];
  },

  // ---- 排序/筛选：没有就返回 [] -----------------------------------------
  getUrlReplace($) {
    return [];
  },

  // ---- 详情：按 detailType 返回；不支持返回 null -------------------------
  async getDetailInfo(item, cheerio) {
    const html = await getHtml(item.jumpUrl);
    const $page = cheerio.load(html);

    const details = [];
    $page('.gallery img').each(function (index, element) {  // TODO 详情页图片
      const $img = $(element);
      details.push({
        type: 'image',
        url: helpElAttr($img, ElementAttr.src),          // 展示地址
        fullUrl: helpElAttr($img, ElementAttr.dataOriginal), // 原图地址，可空
        title: helpElAttr($img, ElementAttr.alt),
      });
    });
    if (details.length === 0) return null;

    return {
      detailType: 'image',                               // image | comic | text | mp4 | m3u8 | series | win
      renderType: 'normal',
      details: details,
      relations: [],
    };
  },

  // ---- 图集可选项：目录（话/卷）。没有目录就不要写这个方法 --------------
  // async getContents(url, cheerio) {
  //   const $page = cheerio.load(await getHtml(url));
  //   const chapters = [];
  //   $page('.chapter a').each(function (index, element) {
  //     const $a = $(element);
  //     chapters.push({
  //       title: helpElText($a),
  //       url: helpElAttr($a, ElementAttr.href),
  //       isCurrent: $a.hasClass('current'),
  //     });
  //   });
  //   return chapters;
  // },

  // ---- 图集可选项：某一话的图片；info 会作为解图参数传给 adapterImageCode --
  // async getComicImages(url, cheerio) {
  //   const $page = cheerio.load(await getHtml(url));
  //   const images = [];
  //   $page('.scramble-page img').each(function (index, element) {
  //     images.push({ url: helpElAttr($(element), ElementAttr.src) });
  //   });
  //   return images;
  // },

  // ---- 文本可选项：正文。html 会由引擎清洗成结构块 ----------------------
  // async getTextContent(url, cheerio) {
  //   const $page = cheerio.load(await getHtml(url));
  //   return {
  //     title: helpElText($page('h1')),
  //     html: $page('.content').html(),
  //     prevUrl: helpElAttr($page('.nav .prev'), ElementAttr.href),
  //     nextUrl: helpElAttr($page('.nav .next'), ElementAttr.href),
  //   };
  // },

  adapterLoadUrl(url) {
    return url;
  },

  adapterSearchUrl(keyword) {
    return this.homeUrl + 'search?q=' + encodeURIComponent(keyword);
  },
});
