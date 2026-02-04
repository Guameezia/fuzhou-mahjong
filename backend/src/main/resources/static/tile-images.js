/**
 * 麻将牌图片映射配置
 * 使用GitHub上的可商用麻将牌图形资源
 * 资源地址: https://github.com/lietxia/mahjong_graphic
 * 
 * 如果GitHub访问慢，可以下载资源到本地 images/tiles 目录
 */

// 使用本地图片资源（推荐，速度更快）
// 如果本地没有图片，可以运行项目根目录下的 download-tiles.sh 脚本下载
const TILE_IMAGE_BASE_URL = 'images/tiles/';
// 或者使用CDN（如果本地图片不可用，取消注释下面这行）
// const TILE_IMAGE_BASE_URL = 'https://cdn.jsdelivr.net/gh/lietxia/mahjong_graphic@main/PNG%20%E4%BD%8D%E5%9B%BE/';

/**
 * 获取麻将牌的图片路径
 * @param {string} type - 牌类型 (WAN, TIAO, BING, WIND, DRAGON, FLOWER)
 * @param {number} value - 牌值 (1-9 或字牌编号)
 * @returns {string} 图片URL
 */
function getTileImageUrl(type, value) {
    // 如果使用本地资源，直接返回本地路径
    if (TILE_IMAGE_BASE_URL.startsWith('images/')) {
        return getLocalTileImagePath(type, value);
    }
    
    // 使用GitHub资源
    const fileName = getTileFileName(type, value);
    if (fileName) {
        return TILE_IMAGE_BASE_URL + fileName;
    }
    return null;
}

/**
 * 获取本地图片路径（需要先下载资源）
 */
function getLocalTileImagePath(type, value) {
    const fileName = getTileFileName(type, value);
    if (fileName) {
        return TILE_IMAGE_BASE_URL + fileName;
    }
    return 'images/tiles/default.png'; // 默认图片
}

/**
 * 根据牌类型和值获取文件名
 * 根据GitHub资源库的命名规则（立直麻将标准）
 * 万子(m): 1m-9m
 * 条子(s): 1s-9s  
 * 饼子(p): 1p-9p
 * 字牌(z): 1z-7z (东南西北白发中)
 * 花牌: 独立文件名 (chun.png, xia.png, qiu.png, dong.png, mei.png, lan.png, zu.png, ju.png)
 */
function getTileFileName(type, value) {
    switch (type) {
        case 'WAN':
            // 一万到九万: 1m.png 到 9m.png
            if (value >= 1 && value <= 9) {
                return `${value}m.png`;
            }
            return null;
        case 'TIAO':
            // 一条到九条: 1s.png 到 9s.png
            if (value >= 1 && value <= 9) {
                return `${value}s.png`;
            }
            return null;
        case 'BING':
            // 一饼到九饼: 1p.png 到 9p.png
            if (value >= 1 && value <= 9) {
                return `${value}p.png`;
            }
            return null;
        case 'WIND':
            // 东南西北: 1z.png 到 4z.png (东=1, 南=2, 西=3, 北=4)
            if (value >= 1 && value <= 4) {
                return `${value}z.png`;
            }
            return null;
        case 'DRAGON':
            // 中发白: 5z.png(中), 6z.png(白), 7z.png(发)
            // value: 1=中, 2=白, 3=发
            if (value === 1) {
                return '5z.png'; // 中
            } else if (value === 2) {
                return '6z.png'; // 白
            } else if (value === 3) {
                return '7z.png'; // 发
            }
            return null;
        case 'FLOWER':
            // 花牌: 春夏秋冬梅兰竹菊
            // value: 1=春, 2=夏, 3=秋, 4=冬, 5=梅, 6=兰, 7=竹, 8=菊
            const flowerFileNames = {
                1: 'chun.png',  // 春
                2: 'xia.png',   // 夏
                3: 'qiu.png',   // 秋
                4: 'dong.png',  // 冬 (注意：和"东"的拼音相同，但文件名不同)
                5: 'mei.png',   // 梅
                6: 'lan.png',   // 兰
                7: 'zu.png',    // 竹
                8: 'ju.png'     // 菊
            };
            return flowerFileNames[value] || null;
        default:
            return null;
    }
}

/**
 * 预加载所有麻将牌图片
 */
function preloadTileImages() {
    const images = [];
    
    // 预加载万条饼 (1-9)
    for (let i = 1; i <= 9; i++) {
        ['WAN', 'TIAO', 'BING'].forEach(type => {
            const url = getTileImageUrl(type, i);
            if (url) {
                const img = new Image();
                img.src = url;
                images.push(img);
            }
        });
    }
    
    // 预加载风牌 (1-4)
    for (let i = 1; i <= 4; i++) {
        const url = getTileImageUrl('WIND', i);
        if (url) {
            const img = new Image();
            img.src = url;
            images.push(img);
        }
    }
    
    // 预加载中发白 (1=中, 2=白, 3=发)
    for (let i = 1; i <= 3; i++) {
        const url = getTileImageUrl('DRAGON', i);
        if (url) {
            const img = new Image();
            img.src = url;
            images.push(img);
        }
    }
    
    // 预加载花牌 (1-8: 春夏秋冬梅兰竹菊)
    for (let i = 1; i <= 8; i++) {
        const url = getTileImageUrl('FLOWER', i);
        if (url) {
            const img = new Image();
            img.src = url;
            images.push(img);
        }
    }
    
    return images;
}

// 导出函数（如果在模块环境中）
if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
        getTileImageUrl,
        getLocalTileImagePath,
        getTileFileName,
        preloadTileImages
    };
}
