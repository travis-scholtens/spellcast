/*! http://mths.be/fromcodepoint v0.1.0 by @mathias */
if (!String.fromCodePoint) {
  (function() {
    var defineProperty = (function() {
      // IE 8 only supports `Object.defineProperty` on DOM elements
      try {
        var object = {};
        var $defineProperty = Object.defineProperty;
        var result = $defineProperty(object, object, object) && $defineProperty;
      } catch(error) {}
      return result;
    }());
    var stringFromCharCode = String.fromCharCode;
    var floor = Math.floor;
    var fromCodePoint = function() {
      var MAX_SIZE = 0x4000;
      var codeUnits = [];
      var highSurrogate;
      var lowSurrogate;
      var index = -1;
      var length = arguments.length;
      if (!length) {
        return '';
      }
      var result = '';
      while (++index < length) {
        var codePoint = Number(arguments[index]);
        if (
          !isFinite(codePoint) ||       // `NaN`, `+Infinity`, or `-Infinity`
          codePoint < 0 ||              // not a valid Unicode code point
          codePoint > 0x10FFFF ||       // not a valid Unicode code point
          floor(codePoint) != codePoint // not an integer
        ) {
          throw RangeError('Invalid code point: ' + codePoint);
        }
        if (codePoint <= 0xFFFF) { // BMP code point
          codeUnits.push(codePoint);
        } else { // Astral code point; split in surrogate halves
          // http://mathiasbynens.be/notes/javascript-encoding#surrogate-formulae
          codePoint -= 0x10000;
          highSurrogate = (codePoint >> 10) + 0xD800;
          lowSurrogate = (codePoint % 0x400) + 0xDC00;
          codeUnits.push(highSurrogate, lowSurrogate);
        }
        if (index + 1 == length || codeUnits.length > MAX_SIZE) {
          result += stringFromCharCode.apply(null, codeUnits);
          codeUnits.length = 0;
        }
      }
      return result;
    };
    if (defineProperty) {
      defineProperty(String, 'fromCodePoint', {
        'value': fromCodePoint,
        'configurable': true,
        'writable': true
      });
    } else {
      String.fromCodePoint = fromCodePoint;
    }
  }());
}

var healthIcon = { glyph: '\u2665', color: 'lightpink' };
var levelIcon = { glyph: '\u26a1', color: 'lightgreen' };

var Shrine = function(multiplier, isWholeWord, frequency, color, name) {
  this.multiplier = multiplier;
  this.isWholeWord = isWholeWord;
  this.frequency = frequency;
  this.color = color;
  this.name = name;
};

var shrines = [
    new Shrine(2, false, 6, 'lightblue', 'Biglyphica'),
    new Shrine(3, false, 3, 'blue', 'Trirunius'),
    new Shrine(2, true, 4, 'pink', 'Duverbia'),
    new Shrine(3, true, 2, 'red', 'Troilogos'),
];

var shrineDistribution = function() {
  return function(ss) {
           var map = {};
           ss.forEach(function(s, i) {
             map[i] = s.frequency;
           });
           return map;
         }(shrines);
};

var FoeKind = function(glyph, name, color, health, speed, value, frequency) {
  this.glyph = glyph;
  this.name = name;
  this.color = color;
  this.health = health;
  this.speed = speed;
  this.value = value;
  this.frequency = frequency;
};

var foeDistribution = function(kinds, depth) {
  var dist = {};
  kinds.slice(0, depth).forEach(
      function(k) {
        this[k.glyph] = k.frequency;
      }.bind(dist));
  return dist;
};

var fovFrom = function(level, loc, radius, callback) {
  level.fov.compute(loc.x, loc.y, radius,
      function(x, y, _1, _2) {
        callback({x: x, y: y});
      });
}

var revealFrom = function(attempt, level, fromLoc) {
  fovFrom(level, fromLoc, 7,
      function(loc) {
        var tile = this[loc.x + ',' + loc.y];
        if (!tile.revealed && level.shrine && loc.x == level.shrine.x && loc.y == level.shrine.y)
          attempt.message('You notice a Shrine of ' + shrines[level.shrine.type].name + '.');
        tile.revealed = true;
      }.bind(level.map));
}

var Attempt = function(state) {
  if (state) {
    this.restore(state);
    this.messages = [];
  } else {
    this.rngState = ROT.RNG.getState();
    this.ascended = false;
    this.balanceVersion = Game.balanceVersion;
    this.complete = false;
    this.player = null;
    this.shrines = shrineDistribution();
    this.rack = [];
    this.spellsCast = [];
  
    this.levels = [_generateMap(1, this.rngState, this.shrines)];
    localStorage['spellyLevelState0'] = JSON.stringify(this.levelState(0));

    var colorBase = ROT.RNG.getUniformInt(80, 255);
    this.player = new Player(this.levels[0],
        this.levels[0].playerStart.x, this.levels[0].playerStart.y,
        'rgb(' + colorBase + ',' + (colorBase - 40) + ',' + (colorBase - 80) + ')');
    this.messages = [];
    revealFrom(this, this.player.level, {x: this.player._x, y: this.player._y});
  }
  this.mode = [];
}

var objectState = function(obj, filterFn, mapFn) {
  var state = [];
  for (var k in obj)
    if (obj.hasOwnProperty(k) && filterFn(obj[k]))
      state.push({k: k, v: mapFn(obj[k])});
  return state;
}

var listState = function(list) {
  var state = [];
  for (var i = 0; i < list.length; i++)
    state.push({k: i, v: list[i].state()});
  return state;
}

var restoreObject = function(obj, state, Class, parentState) {
  for (var i = 0; i < state.length; i++)
    obj[state[i].k] = Class !== undefined ? new Class(state[i].v, parentState) : state[i].v;
}

var restoreMap = function(map, tileData, mapFn) {
  for (var i = 0; i < tileData.length; i++)
    map[tileData[i].k] = mapFn(tileData[i].v);
}

var fov = function(level) {
  return new ROT.FOV.PreciseShadowcasting(function(x, y) {
    return x >= 0 && x < this.width && y >= 0 && y < this.height ?
        this.map[x+','+y] && this.map[x+','+y].passable : false;
  }.bind(level))
}

Attempt.prototype.restore = function(state) {
  this.rngState = state.s;
  this.ascended = state.a;
  this.balanceVersion = 'b' in state ? state.b : -1;
  this.complete = state.f;
  this.levels = [];
  this.player = new Player(state.player);
  this.shrines = state.sh;
  this.rack = state.rack.map(function(c) { return new Corpse(c.g, c.t); });
  this.spellsCast = state.c.map(function(s) {
    return { tiles: s[0], depth: s[1], shrine: s[2] };
  });
}

Attempt.prototype.state = function() {
  return { player: this.player.state(),
      rack: this.rack.map(function(c) { return { g: c.glyph, t: Game.scheduler.getTime() - c.timeOfDeath }; }),
      sh: this.shrines,
      s: this.rngState,
      a: this.ascended,
      b: this.balanceVersion,
      f: this.complete,
      c: this.spellsCast.map(function(s) {
           return [s.tiles, s.depth, s.shrine];
         })
      };
}

Attempt.prototype.restoreLevel = function(state) {
  var i = state.depth - 1;
  this.levels[i] = state;
  this.levels[i].map = {};
  restoreMap(this.levels[i].map, state.tileData,
             function(t) {
               var tile = new Tile(t.c in Game.glyphKind || t.c == '\u00b7' ? '\u00b7' : false);
               if (t.c in Game.glyphKind)
                 tile.corpse = new Corpse(t.c, t.f);
               else
                 tile.glyph = t.c;
               if ('r' in t)
                 tile.revealed = true;
               return tile;
             });
  this.levels[i].fov = fov(this.levels[i]);
  this.levels[i].foes = [];
  restoreObject(this.levels[i].foes, state.foeData, Foe, this.levels[i]);
  for (var j = 0; j < this.levels[i].foes.length; j++)
    this.levels[i].map[this.levels[i].foes[j]._x + ',' + this.levels[i].foes[j]._y].contents =
        this.levels[i].foes[j];
}

Attempt.prototype.levelState = function(i) {
  var state = {
      depth: this.levels[i].depth,
      width: this.levels[i].width,
      height: this.levels[i].height,
      radius: this.levels[i].radius,
      up: this.levels[i].up,
      down: this.levels[i].down,
      shrine: this.levels[i].shrine,
      tileData: objectState(this.levels[i].map,
                            function(t) { return t.glyph !== null; },
                            function(t) {
                              var ts = {};
                              if (t.corpse !== null) {
                                ts.c = t.corpse.glyph;
                                ts.f = Game.scheduler.getTime() - t.corpse.timeOfDeath;
                              } else
                                ts.c = t.glyph;
                              if (t.revealed)
                                ts.r = 1;
                              return ts;
                            }),
      foeData: listState(this.levels[i].foes)
      };
  return state;
}

var ninetyDegreeAdjacentWalls = function(level, loc, dir) {
  var adjDirs = dir == Direction.N || dir == Direction.S ?
                [Direction.E, Direction.W] :
                [Direction.N, Direction.S],
      wallDirs = [];
  adjDirs.forEach(function(adjDir) {
    var dx = adjDir == Direction.E ? 1 :
             adjDir == Direction.W ? -1 : 0,
        dy = adjDir == Direction.S ? 1 :
             adjDir == Direction.N ? -1 : 0;
    if (isWall(level, loc.x + dx, loc.y + dy))
      wallDirs.push(adjDir);
  });
  return wallDirs;
}

var adjacentWalls = function(level, loc) {
  var walls = [];
  [[Direction.E, loc.x+1, loc.y],
   [Direction.W, loc.x-1, loc.y],
   [Direction.N, loc.x, loc.y-1],
   [Direction.S, loc.x, loc.y+1]].forEach(function(dir) {
    if (isWall(level, dir[1], dir[2]))
      walls.push({dir: dir[0], loc: {x: dir[1], y: dir[2]}});
   });
  return walls;
}

var floorSpaces = function(level) {
  var x, y, floor = [];
  for (x = 0; x < level.width; x++)
    for (y = 0; y < level.height; y++)
      if (level.map[x + ',' + y].passable)
        floor.push({x: x, y: y});
  return floor;
}

var _generateMap = function(depth, rngState, shrineDist) {
  var level = { depth: depth },
      dim = level.depth == 1 ? 24 :
            level.depth == 2 ? 36 :
            level.depth == 3 ? 48 :
            level.depth == 4 ? 56 :
            64;
  ROT.RNG.setState(rngState);
  for (var i = 0; i < depth; i++)
    ROT.RNG.getUniform();
  level.width = dim;
  level.height = dim;
  level.radius = Math.sqrt(level.width * level.width + level.height * level.height);
  level.map = {};
  var digger = new ROT.Map.Digger(level.width, level.height);
  var freeCells = [];

  var digCallback = function(x, y, value) {
      var key = x+","+y;
      this.map[key] = new Tile(value ? false : '\u00b7');
      if (!value)
        freeCells.push(key);
  }
  digger.create(digCallback.bind(level));

  var edges = [],
      addEdge = function(loc, dir) {
        if (!(loc.x in edges))
          edges[loc.x] = [];
        if (!(loc.y in edges[loc.x]))
          edges[loc.x][loc.y] = [];
        if (edges[loc.x][loc.y].indexOf(dir) < 0)
          edges[loc.x][loc.y].push(dir);
      },
      addEdges = function(loc, dir) {
        addEdge(loc, dir);
        if (dir == Direction.E)
          addEdge({x: loc.x + 1, y: loc.y}, Direction.W);
        else if (dir == Direction.W)
          addEdge({x: loc.x - 1, y: loc.y}, Direction.E);
        else if (dir == Direction.N)
          addEdge({x: loc.x, y: loc.y - 1}, Direction.S);
        else if (dir == Direction.S)
          addEdge({x: loc.x, y: loc.y + 1}, Direction.N);
      };

  floorSpaces(level).forEach(function(loc) {
    adjacentWalls(level, loc).forEach(function(wall) {
      ninetyDegreeAdjacentWalls(level, wall.loc, wall.dir).forEach(function(adjDir) {
        addEdges(wall.loc, adjDir);
      });
    });
  });

  for (var x = 0; x < level.width; x++)
    for (var y = 0; y < level.height; y++)
      if (x in edges && y in edges[x])
        level.map[x+','+y].glyph = selectWall(wallValue(edges[x][y]));

  level.fov = fov(level);

  var rooms = digger.getRooms();
  var upRoom = ROT.RNG.getUniformInt(0, rooms.length-1);
  var downRoom = upRoom;
  if (rooms.length > 1)
    while (downRoom == upRoom)
      downRoom = ROT.RNG.getUniformInt(0, rooms.length-1);
  var shrineRoom = upRoom;
  if (level.depth == 1)
    do {
      shrineRoom = ROT.RNG.getUniformInt(0, rooms.length-1);
    } while (rooms.length > 2 && (shrineRoom == upRoom || shrineRoom == downRoom));
  else if (ROT.RNG.getUniformInt(0, 1))
    shrineRoom = null;
  else {
    shrineRoom = ROT.RNG.getUniformInt(0, rooms.length-1);
    if (shrineRoom == upRoom || shrineRoom == downRoom)
      shrineRoom = null;
  }

  var upCenter = rooms[upRoom].getCenter(),
      downCenter = rooms[downRoom].getCenter();
  level.up = depth > 1 ?
             { x: upCenter[0] - 1, y: upCenter[1] } :
             { x: -1, y: -1 };
  if (depth == 1)
    level.playerStart = { x: upCenter[0], y: upCenter[1] };
  level.down = { x: downCenter[0], y: downCenter[1] };

  [level.up, level.down].forEach(function(loc) {
    var i = freeCells.indexOf(loc.x + ',' + loc.y);
    if (i >= 0)
      freeCells.splice(i, 1);
  });

  var shrineCenter = shrineRoom !== null ? rooms[shrineRoom].getCenter() : null;
  level.shrine = shrineRoom !== null &&
                     shrines.map(function(_,i) { return shrineDist[i]; })
                            .reduce(function(a,b) { return a+b; }) > 0 ?
                 { x: shrineCenter[0] + 1,
                   y: shrineCenter[1],
                   type: level.depth == 1 ? 2 :
                         ROT.RNG.getWeightedValue(shrineDist) } :
                 null;
  if (level.shrine !== null)
    shrineDist[level.shrine.type]--;

  var kinds = foeDistribution(foeKinds, level.depth);
  if (level.depth > 1) {
    var countTiles = function(ks) {
                       return ks.map(function(k){return k.frequency;})
                                .reduce(function(a,b){return a+b;});
                     },
        levelTiles = countTiles(foeKinds.slice(0, level.depth)),
        totalTiles = countTiles(foeKinds),
        blankScale = levelTiles / totalTiles;
    kinds[shapeshifter.glyph] = shapeshifter.frequency * blankScale;
  }
  level.foes = [];
  var foeLimits = level.depth == 1 ? [2, 2] :
              level.depth == 2 ? [4, 5] :
              level.depth == 3 ? [6, 9] :
              level.depth == 4 ? [9, 13] :
              level.depth <= 26 ? [(level.depth-5)*(level.depth-5)/60 + 10,
                                   (level.depth-5)*(level.depth-5)/60 + 20] :
              [2*level.depth - 36, 2*level.depth - 26];
  for (var i = 0; i < ROT.RNG.getUniformInt(foeLimits[0], foeLimits[1]); i++) {
    var loc = this.newBeingLocation(freeCells);
    var foe = new Foe(Game.glyphKind[ROT.RNG.getWeightedValue(kinds)], level, loc.x, loc.y);
    level.map[loc.x + ',' + loc.y].contents = foe;
    level.foes.push(foe);
  }

  return level;
}

Attempt.prototype.levelBelow = function(level) {
  var index = this.levels.indexOf(level) + 1;
  if (index == this.levels.length)
    this.levels.push(_generateMap(index+1, this.rngState, this.shrines));
  return this.levels[index];
}

Attempt.prototype.levelAbove = function(level) {
  return this.levels[Math.max(0, this.levels.indexOf(level) - 1)];
}

var newBeingLocation = function(freeCells) {
  var index = Math.floor(ROT.RNG.getUniform() * freeCells.length);
  var key = freeCells.splice(index, 1)[0];
  var parts = key.split(",");
  var x = parseInt(parts[0]);
  var y = parseInt(parts[1]);
  return {x: x, y: y};
}

var clearRackSpace = function(display, x, y) {
  for (var i = 0; i < 3; i++)
    display.draw(x - 1 + i, y, '');
}

var rackTileText = function(glyph, color) {
  return '%b{' + color + '}%c{black}' + glyph;
}

var drawRackTile = function(display, x, y, glyph, color, shrine) {
  var value = Game.values[glyph],
      ax,
      digits;
  if (value < 10) {
    ax = x - 0.5;
    digits = value > 0 ? [value] : [];
  } else {
    ax = x - 0.75;
    digits = [Math.floor(value/10), value % 10];
  }
  if (glyph === '.')
    display.draw(ax, y, '', 'black', color);
  else
    display.drawText(ax, y, rackTileText(glyph, color));
  if (shrine)
    display.draw(ax, y+1, '\u2501', shrine.color);
  if (digits.length > 0)
    for (var i = 0; i < digits.length; i++) {
      display.drawText(ax + 0.75 * i, y,
          rackTileText(String.fromCodePoint(0x2080 + digits[i]), color));
      if (shrine)
        display.draw(ax + 1 + 0.75 * i, y+1, '\u2501', shrine.color);
    }
  else {
    display.draw(ax + 1, y, '', 'black', color);
    if (shrine)
      display.draw(ax + 1, y+1, '\u2501', shrine.color);
  }
}

Attempt.prototype.message = function(text) {
  this.messages.push(text);
}

var Direction = {
  N: 1, // 0b0001
  E: 2, // 0b0010
  S: 4, // 0b0100
  W: 8  // 0b1000
}

var selectWall = function(neighborWallDirections) {
  switch (neighborWallDirections) {
    case  3 /*0b0011*/: return '\u255a';
    case  5 /*0b0101*/: return '\u2551';
    case  6 /*0b0110*/: return '\u2554';
    case  7 /*0b0111*/: return '\u2560';
    case  9 /*0b1001*/: return '\u255d';
    case 10 /*0b1010*/: return '\u2550';
    case 11 /*0b1011*/: return '\u2569';
    case 12 /*0b1100*/: return '\u2557';
    case 13 /*0b1101*/: return '\u2563';
    case 14 /*0b1110*/: return '\u2566';
    case 15 /*0b1111*/: return '\u256c';
    default: return '\u25a2';
  }
}

var exposed = function(level, x, y) {
  if (level.map[x + ',' + y].passable)
    return true;
  var ncs = [[-1,-1], [-1,0], [-1,1],
             [0,-1], [0,1],
             [1,-1], [1,0], [1,1]];
  for (var i = 0; i < ncs.length; i++) {
    var nx = x + ncs[i][0],
        ny = y + ncs[i][1];
    if (nx >= 0 && nx < level.width && ny >= 0 && ny < level.height)
      if (level.map[nx + ',' + ny].passable)
        return true;
  }
  return false;
}

var isWall = function(level, x, y) {
  if (x < 0 || x >= level.width || y < 0 || y >= level.height)
    return false;
  return exposed(level, x, y) && !level.map[x + ',' + y].passable;
}

var wallValue = function(dirs) {
  return dirs.reduce(function(x, y) { return x | y; }, 0);
}

var partOfFlatWall = function(level, x, y, dir) {
  var cy = dir == Direction.W || dir == Direction.E,
      cx = dir == Direction.N || dir == Direction.S;
  return (cx && (!isWall(level, x-1, y) || !isWall(level, x+1, y))) ||
         (cy && (!isWall(level, x, y-1) || !isWall(level, x, y+1)));
}

var meterCellEighths = function(rangeLow, rangeHigh, value, scale) {
  var range = rangeHigh - rangeLow,
      value = Math.max(0, Math.min(range, value - rangeLow)),
      totalEighths = Math.round((value/range) * scale * 8),
      remainder = totalEighths % 8,
      dividend = (totalEighths - remainder)/8,
      cells = [],
      i;
  for (i = 0; i < dividend; i++)
    cells.push(8);
  cells.push(remainder);
  return cells;
}

var fractionalBlock = function(eighths) {
  if (eighths === 0) return '';
  return String.fromCharCode(0x2580 + eighths);
}

var drawMeter = function(display, x, y, dy, rangeLow, rangeHigh, value, glyph, color) {
  var i,
      j,
      cells = meterCellEighths(rangeLow, rangeHigh, value, dy);
  for (i = 0; i < cells.length; i++)
    display.draw(x, y+dy-i, fractionalBlock(cells[i]), color, 'darkgray');
  for (j = i; j < dy; j++)
    display.draw(x, y+dy-j, fractionalBlock(8), 'darkgray');
  display.draw(x, y, glyph, color);
}

var drawScore = function(display, xmax, y, score) {
  var text = score + '',
      x = Math.floor((xmax - text.length)/2);
  display.drawText(x, y, text);
}

var wrapMessages = function(messages, width) {
  var i,
      prefix = ['>'],
      tokens,
      line,
      indent = ' ',
      wrapped = [];
  for (i = 0; i < messages.length; i++) {
    tokens = prefix.concat(messages[i].split(' '));
    while (tokens.length > 0) {
      line = tokens.shift();
      while (tokens.length > 0 && line.length + tokens[0].length + 1 < width)
        line = [line, tokens.shift()].join(' ');
      wrapped.push(line);
      if (tokens.length > 0)
        tokens.unshift(indent);
    }
  }
  return wrapped;
}

var clearSavedGame = function() {
  var i = 0, key;
  delete localStorage.spellyGameState;
  while (true) {
    key = 'spellyLevelState' + i;
    if (!(key in localStorage))
      break;
    delete localStorage[key];
    i++;
  }
  Game.scheduler.clear();
  Game.attempt = null;
}

var handle = function(e) {
  var t = new Date().getTime(),
      waiting = false,
      handled = false,
      acted = false;
  if ((t - this.lastEvent) < 100)
    waiting = true;
  else if (Game.titleButtons) {
    if (handleTitleClick(e)) {
      delete Game.titleButtons;
      acted = true;
      handled = true;
    }
  } else if (Game.showingTutorial || Game.showingHighScores) {
    Game.showTitle();
    Game.showingTutorial = false;
    Game.showingHighScores = false;
    acted = true;
    handled = true;
  } else if (Game.attempt.complete &&
             (t - Game.completedAt) > 1000) {
    clearSavedGame();
    if (Game.newHighScoreIndex >= 0)
      Game.showHighScores(Game.newHighScoreIndex);
    else
      Game.showTitle();
    acted = true;
    handled = true;
  } else if (!Game.attempt.complete > 0 && Game.attempt.player.acting) {
    acted = Game.attempt.player.handleEvent(e);
    handled = true;
  }
  if (!waiting)
    this.lastEvent = t;
  if (acted) {
    this.acting = false;
    Game.engine.unlock();
  }
  if (handled)
    e.preventDefault();
}

var surroundText = function(text, color) {
  return '%c{' + color + '}' + text + '%c{}';
}

var formatCastSpell = function(text, shrineType) {
  var shrine = shrineType !== null ? shrines[shrineType] : null,
      bestLetterValue =
          text.split('').map(function(c) { return c in Game.values ? Game.values[c] : 0; }).reduce(function(a,b) { return Math.max(a,b); }),
      bestLetterIndex = bestLetterValue > 0 ?
          text.split('').findIndex(function(c) { return c in Game.values && Game.values[c] == bestLetterValue; }) :
          1  /* the blank after the opening '(' in the case of a word consisting entirely of blanks */,
      upperCasedText = text.toUpperCase();

  if(shrine == null) return upperCasedText;

  if (shrine.isWholeWord)
    return surroundText(upperCasedText, shrine.color);
  else
    return upperCasedText.substr(0, bestLetterIndex) +
        surroundText(upperCasedText[bestLetterIndex], shrine.color) +
        upperCasedText.substr(bestLetterIndex+1);
}

var formatSpell = function(castSpell, value) {
  return formatCastSpell(checkWord(castSpell.tiles), castSpell.shrine) + ' (' + value + ')';
};

var drawButton = function(display, button, origin, xmax, ymax) {
  for (x = 0; x < button.length + 2; x++)
    for (y = 0; y < 3; y++) {
      glyph = x == 0 ? (y == 0 ? '\u256d' :
                        y == 2 ? '\u2570' :
                        '\u2502') :
              x == button.length + 1 ? (y == 0 ? '\u256e' :
                                        y == 2 ? '\u256f' :
                                        '\u2502') :
              y == 0 || y == 2 ? '\u2500' :
              '';
      display.draw(origin.x + xmax/2 - button.length/2 + x - 1,
                   origin.y + ymax - 4 + y,
                   glyph);
    }
  display.drawText(origin.x + xmax/2 - button.length/2 + button.search(/[^ ]/),
                   origin.y + ymax - 3,
                   button);
}

var Game = {
    version: 9,
    balanceVersion: 6,
    display: null,
    engine: null,
    scheduler: null,
    attempt: null,
    rack_size: 6,
    rackTileLocations: {},
    completedAt: 0,

    init: function() {
        this.display = new ROT.Display({width: 26, height: 26, fontSize: 24, fontFamily: 'SpellyFreeMono', spacing: 1});
        var canvas = this.display.getContainer();
        canvas.style.width = '100%';
        canvas.style.height = '100%';
        document.getElementById("main").appendChild(canvas);
        if (canvas.clientHeight > canvas.height)
          canvas.style.height = canvas.height + 'px';
        if (canvas.clientWidth > canvas.clientHeight)
          canvas.style.width = (0.625 * canvas.height) + 'px';

        this.lastEvent = 0;
        window.addEventListener("keydown", handle);
        window.addEventListener("mouseup", handle);
        window.addEventListener("touchend", handle);

        this.values = {'.': 0};
        for (var i = 0; i < foeKinds.length; i++)
          this.values[foeKinds[i].glyph] = foeKinds[i].value;

        this.glyphKind = {};
        foeKinds.forEach(
            function(k) {
              this[k.glyph] = k;
            }.bind(this.glyphKind));
        this.glyphKind[shapeshifter.glyph] = shapeshifter;

        this.scheduler = new ROT.Scheduler.Speed();

        this.engine = new ROT.Engine(this.scheduler);

        this.showTitle();
    },

    save: function() {
      var i = this.attempt.player.level.depth-1,
          gameState = { v: this.version,
                        rng: ROT.RNG.getState(),
                        attempt: this.attempt.state() },
          levelState = this.attempt.levelState(i),
          gameStr = JSON.stringify(gameState),
          levelStr = JSON.stringify(levelState);
      localStorage['spellyGameState'] = gameStr;
      localStorage['spellyLevelState' + i] = levelStr;
    },

    newAttempt: function(state) {
      this.scheduler.clear();
      this.attempt = new Attempt(state);
      if (state) {
        var i = 0;
        while (true) {
          var key = 'spellyLevelState' + i;
          if (!(key in localStorage))
            break;
          this.attempt.restoreLevel(JSON.parse(localStorage[key]));
          i++;
        }
        this.attempt.player.level = this.attempt.levels[state.player.levelIndex];
      }
      this._drawWholeMap(this.attempt);

      Game.scheduler.add(this.attempt.player, true);
      Game.schedule(this.attempt.player.level.foes);
    },

    schedule: function(foes) {
      foes.forEach(function(f) { this.add(f, true); }.bind(this.scheduler));
    },

    unschedule: function(foes) {
      foes.forEach(this.scheduler.remove.bind(this.scheduler));
    },

    modal: function(size, button) {
      var display = this.display.getOptions();
      var xmax = size.x,
          ymax = size.y;
      var origin = {
          x: (display.width - xmax)/2,
          y: (display.height - ymax)/2 };
      var result = {
          center: {
              x: origin.x + xmax/2,
              y: origin.y + ymax/2 } };
      for (var x = 0; x < xmax; x++)
        for (var y = 0; y < ymax; y++) {
          var glyph = x == 0 ? (y == 0 ? '\u256d' :
                                y == 2 ? '\u251c' :
                                y == ymax - 1 ? '\u2570' :
                                '\u2502') :
                      x == 1 ? (y == 0 ? '\u2500' :
                                y == 1 ? '\u2613' :
                                y == 2 ? '\u2500' :
                                y == ymax - 1 ? '\u2500' :
                                '') :
                      x == 2 ? (y == 0 ? '\u252c' :
                                y == 1 ? '\u2502' :
                                y == 2 ? '\u2518' :
                                y == ymax - 1 ? '\u2500' :
                                '') :
                      x == xmax - 1 ? (y == 0 ? '\u256e':
                                       y == ymax - 1 ? '\u256f' :
                                       '\u2502') :
                      y == 0 || y == ymax  - 1 ? '\u2500' :
                      '';
          this.display.draw(origin.x + x,
                            origin.y + y,
                            glyph);
        }
      result.closeButton = Math.round(origin.x) == origin.x ?
          [{ x: origin.x + 1,
             y: origin.y + 1 }] :
          [{ x: origin.x + 0.5,
             y: origin.y + 1 },
           { x: origin.x + 1.5,
             y: origin.y + 1 }];
      if (button !== undefined) {
        drawButton(this.display, button, origin, xmax, ymax);
        result.actButton = [];
        for (x = 0; x < button.length; x++)
          result.actButton.push({x: origin.x + xmax/2 - button.length/2 + x,
                                 y: origin.y + ymax - 3});
      }
      return result;
    },

    showTextScreen: function(lines) {
      var display = this.display.getOptions(),
          y = 0,
          i;
      this.display.clear();
      for (i = 0; i < lines.length; i++)
        y += this.display.drawText(0, y, lines[i], display.width) + 1;
    },

    showTutorial: function() {
      this.showingTutorial = true;
      this.showTextScreen([
          'Fight monsters',
          'Use their corpses to cast spells for more power',
          'Start with the spell "AA" (a kind of lava)',
          'Spells cast on a shrine (like %b{' + shrines[2].color + '} %b{}) are worth more',
          '7-corpse spells get a bonus',
          'Cast the spell of "YENDOR" to win',
          'Source: bitbucket.org/tps12/spellcast'
      ].map(function(line) { return '\u2022 ' + line; }));
    },

    logoColor: function(x, y) {
      var r = 255,
          g = y > 0.5 ? 255 : Math.floor(255 * y/0.5),
          b = y < 0.5 ? 0 : Math.floor(255 * (y - 0.5)/0.5);
      return 'rgb(' + [r,g,b].join([separator=',']) + ')';
    },

    drawLogo: function(y) {
      var i, j;
      this.display.clear();
      for (i = 0; i < logo.length; i++)
        for (j = 0; j < logo[i].length; j++)
          this.display.draw(j+1, y+i, logo[i][j],
                            this.logoColor(j/logo[i].length, i/logo.length));
      return y + logo.length;
    },

    showTitle: function() {
      var spacing = 4,
          origin = { x: 0, y: 0 },
          xmax = this.display.getOptions().width,
          ymax,
          x,
          y = 3,
          i,
          gameInProgress = function(gameState) {
                             if (gameState === undefined) return undefined;
                             var state = JSON.parse(localStorage.spellyGameState);
                             return state.v === Game.version && !state.attempt.f ?
                                 state : undefined;
                           }(localStorage.spellyGameState),
          buttons = [
            { text: gameInProgress !== undefined ? 'Resume  Game' : '  New Game  ',
              action: gameInProgress !== undefined ?
                      function() {
                        Game.display.clear();
                        ROT.RNG.setState(gameInProgress.rng);
                        Game.newAttempt(gameInProgress.attempt);
                        Game.engine.start();
                      } :
                      function() {
                        Game.display.clear();
                        clearSavedGame();
                        Game.newAttempt();
                      } },
            { text: 'Instructions',
              action: function() { Game.showTutorial.call(Game); } },
          ].concat(this.loadHighScores().length > 0 ?
                   [ { text: 'High  Scores',
                       action: function() { Game.showHighScores.call(Game, -1); } } ] :
                   []);

      y = this.drawLogo(y) + 1 + spacing;

      this.titleButtons = buttons;
      for (i = 0; i < this.titleButtons.length; i++) {
        ymax = y + spacing * i;
        drawButton(this.display, this.titleButtons[i].text, origin, xmax, ymax);
        this.titleButtons[i].locs = [];
        for (x = 0; x < this.titleButtons[i].text.length; x++)
          this.titleButtons[i].locs.push(
              {x: origin.x + xmax/2 - this.titleButtons[i].text.length/2 + x,
               y: origin.y + ymax - 3});
      }
    },

    loadHighScores: function() {
      var version = 0,
          scoresKey = 'spellyHighScores',
        data = (scoresKey in localStorage ?
                JSON.parse(localStorage[scoresKey]) : undefined);
      return data !== undefined && data[0] == version ? data[1] : [];
    },

    saveHighScores: function(scores) {
      var version = 0,
          scoresKey = 'spellyHighScores';
      localStorage[scoresKey] = JSON.stringify([version, scores]);
    },

    showHighScores: function(index) {
      var scores = sortScores(this.loadHighScores()).slice(0, 8),
          i,
          y,
          victoryColor,
          depth,
          total,
          bestWord,
          rebalanceVersion,
          w = this.display.getOptions().width;
      this.showingHighScores = true;
      this.display.clear();
      for (i = 0; i < scores.length; i++) {
        y = 1 + 3 * i;
        victoryColor = scores[i][0];
        depth = scores[i][1] + '';
        total = scores[i][2] + '';
        bestWord = scores[i][3];
        rebalanceVersion = scores[i][4];
        if (i == index) {
          this.display.draw(0, y, '\u2571', 'yellow');
          this.display.draw(0, y+1, '\u2572', 'yellow');
          this.display.draw(w-1, y, '\u2572', 'yellow');
          this.display.draw(w-1, y+1, '\u2571', 'yellow');
        }
        if (rebalanceVersion === undefined || rebalanceVersion < Game.balanceVersion)
          this.display.draw(i == index ? 1 : 0, y, '*', 'gray');
        this.display.drawText(2, y, (i+1) + '.');
        this.display.draw(4, y,
                          victoryColor ? '@' : '\u2620',
                          victoryColor || 'gray');
        this.display.drawText(6, y, ' on ' + depth + ', ' + total);
        if (bestWord)
          this.display.drawText(4, y+1, bestWord);
      }
    },

    completeGame: function(attempt, won) {
      if (won)
        attempt.ascended = true;
      attempt.complete = true;
      Game.completedAt = new Date().getTime();
      Game.save();
      Game.newHighScoreIndex = maybeAddScore(attempt);
    },

    showGameSummary: function(attempt) {
      var player = attempt.player,
          level = player.level,
          values = spellValues(attempt.spellsCast),
          bestSpellIndex = values.indexOf(Math.max.apply(Math, values)),
          lines = [
              (attempt.ascended ? 'Won at' : player.getHealth() <= 0 ? 'Died at' : 'At') + ' depth ' + level.depth,
              'Spells cast: ' + attempt.spellsCast.length,
              'Total value: ' + values.reduce(function(a,b) { return a+b; }, 0),
              ].concat(
                  bestSpellIndex >= 0 ? [
                      'Best spell: ' +
                          formatSpell(attempt.spellsCast[bestSpellIndex],
                                      values[bestSpellIndex])
                  ] : []);
      this.showTextScreen(lines);
    },

    _drawWholeMap: function(attempt) {
      var level = attempt.player.level;
      var display = this.display.getOptions();
      for (var y = 0; y < display.width; y++)
        this.display.draw(y, 0, '');
      this.display.drawText(0, 0, 'D: ' + attempt.player.level.depth);
      var levelText = 'L: ' +
          experienceLevelDefinitions.indexOf(levelFor(attempt.player.experience));
      this.display.drawText(display.width - levelText.length, 0, levelText);
      drawScore(this.display, display.width, 0, attempt.player.experience);
      for (var dx = 1; dx < display.width - 1; dx++)
        for (var dy = 1; dy < display.height - 3; dy++) {
          var x = attempt.player.getX() - display.width/2 + dx,
              y = attempt.player.getY() - display.height/2 + dy,
              glyph = '',
              color = 'black',
              bg = 'black';
          if ((0 <= x && x < level.width) &&
              (0 <= y && y < level.height)) {
            if (x == attempt.player._x && y == attempt.player._y)
              glyph = '@', color = attempt.player.color;
            else {
              var tile = level.map[x + ',' + y];
              glyph = '', color = 'white';
              if (tile && tile.revealed) {
                if (tile.contents != null) {
                  glyph = tile.contents.effectiveKind().glyph;
                  color = tile.contents.effectiveKind().color;
                  if (!tile.contents.revealed)
                    attempt.player.path.length = 0;
                  tile.contents.revealed = true;
                } else if (tile.corpse != null) {
                  if (tile.corpse.rotted()) {
                    attempt.message('The ' + Game.glyphKind[tile.corpse.glyph].name +
                                    ' corpse rots away to nothing.');
                    tile.corpse = null;
                    glyph = tile.glyph;
                  } else {
                    glyph = tile.corpse.glyph == '.' ? '\u25a0' : tile.corpse.glyph;
                    color = tile.corpse.rotting() ? 'darkgreen' : 'darkgray';
                    if (this.display.lastDrawnAt !== undefined &&
                        tile.corpse.rotting() && !tile.corpse.rotting(this.display.lastDrawnAt))
                      attempt.message('The ' + Game.glyphKind[tile.corpse.glyph].name +
                                      ' corpse begins to rot.');
                  }
                } else if (x == level.up.x && y == level.up.y)
                  glyph = '\u261d';
                else if (x == level.down.x && y == level.down.y)
                  glyph = '\u261f';
                else
                  glyph = tile.glyph;
                if (level.shrine && x == level.shrine.x && y == level.shrine.y)
                  bg = shrines[level.shrine.type].color;
              }
            }
          }
          this.display.draw(dx, dy, glyph, color, bg);
        }
      var messages = wrapMessages(attempt.messages, display.width);
      var messageCount = messages.length;
      for (var i = 0; i < messageCount; i++) {
        for (var y = 0; y < display.width; y++)
          this.display.draw(y, i + 1, '');
        this.display.drawText(messages[i][0] === ' ' ? 2 : 0, i + 1, messages[i]);
      }
      var meterTop = messageCount + 1,
          meterHeight = display.height - 3 - messageCount - 2;
      drawMeter(this.display, 0, meterTop, meterHeight,
                0, 50, 50 - attempt.player.woundedness,
                healthIcon.glyph, healthIcon.color);
      var expLevelIndex = experienceLevelDefinitions.indexOf(levelFor(attempt.player.experience));
      drawMeter(this.display, display.width - 1, meterTop, meterHeight,
                experienceLevelDefinitions[expLevelIndex-1].expThreshold,
                experienceLevelDefinitions[expLevelIndex].expThreshold,
                expLevelIndex < experienceLevelDefinitions.length - 1 ?
                    attempt.player.experience :
                    experienceLevelDefinitions[expLevelIndex].expThreshold,
                levelIcon.glyph, levelIcon.color);
      this.drawRackTiles(level.map[attempt.player._x + ',' + attempt.player._y].corpse);
      this.display.lastDrawnAt = this.scheduler.getTime();
    },

    drawRackTiles: function(corpse) {
      var display = this.display.getOptions();
      var right = 20;
      for (var dx = 0; dx < right; dx++) {
        var glyph = dx == 0 ? '\u2514' :
                    dx == right - 1 ? '\u2518' :
                    '\u2500';
        this.display.draw(dx, display.height - 1, glyph, "tan");
      }
      this.rackTileLocations.length = 0;
      for (var i = 0; i < this.rack_size; i++)
        clearRackSpace(this.display, 2 + 3 * i, display.height - 2);
      for (var i = 0; i < this.attempt.rack.length; i++) {
        drawRackTile(
            this.display, 2 + 3 * i, display.height - 2, this.attempt.rack[i].glyph, 'tan', null);
        for (var j = 0; j < 2; j++)
          this.rackTileLocations[2 + 3 * i + j] = i;
      }

      var left = 21;
      for (var dx = left; dx < display.width; dx++) {
        var glyph = dx == left ? '\u2514' :
                    dx == display.width - 1 ? '\u2518' :
                    '\u2500';
        this.display.draw(dx, display.height - 1, glyph, "gray");
      }

      clearRackSpace(this.display, 23, display.height - 2);
      if (corpse != null) {
        drawRackTile(
            this.display, 23, display.height - 2, corpse.glyph, 'gray', null);
        for (var j = 0; j < 2; j++)
          this.rackTileLocations[23 + j] = 6;
      }
    }
};

var Corpse = function(glyph, timeSinceDeath) {
  this.glyph = glyph;
  this.timeOfDeath = Game.scheduler.getTime() - timeSinceDeath;
};

Corpse.prototype.rotting = function(atTime) {
  if (atTime === undefined)
    atTime = Game.scheduler.getTime();
  return (atTime - this.timeOfDeath) > 0.9;
};

Corpse.prototype.rotted = function() {
  return (Game.scheduler.getTime() - this.timeOfDeath) > 1;
};

var Tile = function(glyph) {
  this.passable = glyph !== false;
  this.glyph = this.passable ? glyph : null;
  this.revealed = false;
  this.contents = null;
  this.corpse = null;
};

var Player = function(levelOrState, x, y, color) {
  if (!('depth' in levelOrState)) {
    this.restore(levelOrState);
  } else {
    this.level = levelOrState;
    this._x = x;
    this._y = y;
    this.color = color;
    this.experience = 0;
    this.woundedness = 0;
  }
  this.modalTiles = [];
  this.path = [];
  this.acting = false;
}

Player.prototype.restore = function(state) {
  this._x = state.x;
  this._y = state.y;
  this.color = state.color;
  this.experience = state.experience;
  this.woundedness = state.woundedness;
}

Player.prototype.state = function() {
  return {
      x: this._x,
      y: this._y,
      color: this.color,
      experience: this.experience,
      woundedness: this.woundedness,
      levelIndex: this.level.depth - 1 };
}

Player.prototype.getSpeed = function() {
  return levelFor(this.experience).speed;
}

Player.prototype.getHealth = function() {
  return 50 - this.woundedness;
}
    
Player.prototype.getX = function() { return this._x; }
Player.prototype.getY = function() { return this._y; }

var walkPath = function(attempt) {
  var player = attempt.player,
      path = player.path,
      loc = path.length > 0 ? path.shift() : null,
      level = player.level,
      map = level.map,
      tile = loc != null ? map[loc[0] + ',' + loc[1]] : { passable: false },
      shrine = null,
      messageCount = attempt.messages.length;
  if (tile.passable && tile.revealed && tile.contents === null) {
    player._x = loc[0];
    player._y = loc[1];
    if (takeCorpse(map, player._x, player._y, attempt))
      path.length = 0;
    if (useStairs(attempt))
      path.length = 0;
    if (level.shrine && player._x == level.shrine.x && player._y == level.shrine.y)
      path.length = 0;
    revealFrom(attempt, player.level, {x: player._x, y: player._y});
    if (attempt.messages.length > messageCount)
      path.length = 0;
    return true;
  }
  if (loc !== null)
    path.unshift(loc);
  return false;
}

Player.prototype.act = function() {
  Game.engine.lock();
  if (Game.attempt.complete)
    Game.showGameSummary(Game.attempt);
  else {
    this.acting = true;
    if (function() {
      var messageCount = Game.attempt.messages.length;
      Game._drawWholeMap(Game.attempt);
      return Game.attempt.messages.length > messageCount;
    }())
      this.path.length = 0;
    else
      Game.attempt.messages.length = 0;
    if (this.path.length > 0 && walkPath(Game.attempt))
      return { then: function(cb) {
                       Game.engine.unlock();
                       setTimeout(function() { cb(); }, 10);
                     } };
    else
      Game.save();
  }
}

Player.prototype.attack = function(foe) {
  var damage = Math.min(
      Math.max(0, Math.floor(ROT.RNG.getNormal(levelFor(this.experience).meanDamage, 2))),
      foe.health);
  foe.health -= damage;
  return damage;
}

takeCorpse = function(map, x, y, attempt) {
  var tile = map[x + ',' + y];
  if (tile.corpse != null) {
    if (attempt.rack.length < Game.rack_size) {
      var name = Game.glyphKind[tile.corpse.glyph].name;
      attempt.rack.push(tile.corpse);
      tile.corpse = null;
      Game.attempt.message('You take the ' + name + ' corpse.');
    }
    return true;
  }
  return false;
}

dropCorpse = function(map, x, y, excludeLocs, corpse) {
  var q = [[x,y]];
  var seen = {};
  while (q.length > 0) {
    var u = q.shift();
    var key = u[0] + ',' + u[1];
    if (map[key].passable && map[key].corpse == null &&
        excludeLocs.every(function(loc) {
                            return loc.x != u[0] || loc.y != u[1];
                          })) {
      map[key].corpse = corpse;
      return;
    }
    seen[key] = true;
    for (var dx = -1; dx <= 1; dx++)
      for (var dy = -1; dy <= 1; dy++)
        if (dx != 0 || dy != 0) {
          var nx = u[0] + dx, ny = u[1] + dy;
          var nkey = nx + ',' + ny;
          if (nkey in map && !(nkey in seen))
            q.push([nx, ny]);
        }
  }
}

var useStairs = function(attempt) {
  var player = attempt.player,
      level = player.level,
      nextExists,
      stairLoc;
  if (player._x == level.down.x && player._y == level.down.y) {
    player.level.shrine = null;
    Game.save();
    nextExists = attempt.levels.indexOf(level) + 1 < attempt.levels.length;
    player.level = attempt.levelBelow(level);
    stairLoc = attempt.player.level.up;
    if (nextExists)
      attempt.message('You descend deeper into the dungeon.');
    else {
      player.woundedness = 0;
      attempt.message('You descend deeper into the dungeon and your health is restored.');
    }
  }
  else if (player._x == level.up.x && player._y == level.up.y) {
    Game.save();
    player.level = attempt.levelAbove(level);
    stairLoc = player.level.down;
    attempt.message('You climb back towards the surface.');
  }
  if (player.level != level) {
    Game.unschedule(level.foes);
    Game.schedule(player.level.foes);
    player._x = stairLoc.x;
    player._y = stairLoc.y;
    Game.save();
    return true;
  }
  return false;
}

var dealDamage = function(attempt, loc, foe) {
  var player = attempt.player,
      level = player.level,
      map = level.map,
      foes = level.foes,
      damage = player.attack(foe),
      name = Game.glyphKind[foe.effectiveKind().glyph].name;
  if (foe.health == 0) {
    map[loc.x + ',' + loc.y].contents = null;
    dropCorpse(map, loc.x, loc.y,
               [level.up, level.down, {x: player._x, y: player._y}],
               new Corpse(foe.kind.glyph, 0));
    Game.scheduler.remove(foe);
    foes.splice(foes.indexOf(foe), 1);
    attempt.message('You kill the ' + name + '!');
  }
  else if (damage > 0) {
    attempt.message('You hit the ' + name + '! %c{red}-' + damage);
    if (foe.kind === shapeshifter)
      foe.hurtSinceShift = true;
  }
  else
    attempt.message('You miss the ' + name + '.');
}

Player.prototype.keyPress = function(code, level) {
    if (Game.attempt.mode.length > 0) return false;

    var keyMap = {};
    keyMap[38] = 0;
    keyMap[39] = 1;
    keyMap[40] = 2;
    keyMap[37] = 3;

    /* one of numpad directions? */
    if (!(code in keyMap)) { return false; }

    /* is there a free space? */
    var dir = ROT.DIRS[4][keyMap[code]];
    var newX = this._x + dir[0];
    var newY = this._y + dir[1];
    var newKey = newX + "," + newY;
    if (!(newKey in level.map && level.map[newKey].passable))
      return false;

    var contents = level.map[newKey].contents;
    if (contents != null)
      dealDamage(Game.attempt, { x: newX, y: newY }, contents);
    else {
      this._x = newX;
      this._y = newY;
      takeCorpse(level.map, this._x, this._y, Game.attempt);
      useStairs(Game.attempt);
      revealFrom(Game.attempt, this.level, {x: this._x, y: this._y});
    }
    return true;
}

var levelFor = function(experience) {
  var i;
  for (i = 0; i < experienceLevelDefinitions.length; i++)
    if (experience < experienceLevelDefinitions[i].expThreshold)
      return experienceLevelDefinitions[i];
  return experienceLevelDefinitions[experienceLevelDefinitions.length-1];
}

var findWordWithBlank = function(word) {
  var matches = [],
      pattern = new RegExp('^' + word + '$'),
      parenthesizeBlankMatch = function(word, entry) {
        var result = '';
        for (var i = 0; i < word.length; i++)
          result += word[i] == '.' ? '(' + entry[i] + ')' : entry[i];
        return result;
      },
      pushMatches = function(word, pattern, list) {
        for (var entry in list)
          if (list.hasOwnProperty(entry) && entry.match(pattern))
            matches.push(parenthesizeBlankMatch(word, entry));
      };
  pushMatches(word, pattern, words_am);
  pushMatches(word, pattern, words_nz);
  matches.sort();
  return matches[0];
}

var checkWord = function(word) {
  return word.indexOf('.') >= 0 ?
      findWordWithBlank(word) :
      word == 'yendor' || word in words_am || word in words_nz ? word : false;
}

var spellValue = function(spell, shrineType) {
  var score = 0,
      shrine = shrineType !== null ? shrines[shrineType] : null,
      bestLetterValue =
          spell.split('').map(function(c) { return Game.values[c]; }).reduce(function(a,b) { return Math.max(a,b); }),
      bestLetterIndex = spell.split('').findIndex(function(c) { return Game.values[c] == bestLetterValue; }),
      i;
  for (i = 0; i < spell.length; i++)
    score += Game.values[spell[i]] *
             (shrine && (shrine.isWholeWord || i == bestLetterIndex) ? shrine.multiplier : 1);
  if (spell.length >= 7)
    score += 50;
  return score;
}

var spellValues = function(spellsCast) {
  return spellsCast.map(function(castSpell) {
                          return spellValue(castSpell.tiles, castSpell.shrine);
                        });
};

var sortScores = function(scores) {
  return scores.sort(function(a, b) {
                       return a[4] == Game.balanceVersion && b[4] != Game.balanceVersion ? -1 :
                              b[4] == Game.balanceVersion && a[4] != Game.balanceVersion ? 1 :
                              a[0] && !b[0] ? -1 :
                              b[0] && !a[0] ? 1 :
                              b[2] - a[2];
                     });
}

var maybeAddScore = function(attempt) {
  var values = spellValues(attempt.spellsCast),
      bestSpellIndex = values.indexOf(Math.max.apply(Math, values)),
      score = [
          attempt.ascended ? attempt.player.color : null,
          attempt.player.level.depth,
          values.reduce(function(a,b) { return a+b; }, 0),
          bestSpellIndex >= 0 ?
              formatSpell(attempt.spellsCast[bestSpellIndex],
                          values[bestSpellIndex]) :
              null,
          attempt.balanceVersion,
      ],
      scores =
          sortScores(Game.loadHighScores()
                         .concat(score[2] > 0 ? [score] : [])).slice(0, 12);
  Game.saveHighScores(scores);
  return scores.indexOf(score);
};

Player.prototype.clickRackTile = function(index, map) {
  var result,
      button,
      width,
      height;
  if (index < Game.attempt.rack.length) {
    var remaining = Game.attempt.rack.splice(index);
    this.modalTiles.push({corpse: remaining.shift(), fromRack: true, color: 'tan'});
    Game.attempt.rack = Game.attempt.rack.concat(remaining);
  } else if (index == 6) {
    var key = Game.attempt.player._x + ',' + Game.attempt.player._y
    var corpse = map[key].corpse;
    if (corpse != null) {
      this.modalTiles.push({corpse: corpse, fromRack: false, color: 'gray'});
      map[key].corpse = null;
    }
  }
  if (this.modalTiles.length > 0) {
    button = this.modalTiles.length > 1 ? 'Cast' :
             this.modalTiles[0].fromRack ? 'Drop' :
             undefined;
    width = button !== undefined ? Math.min(26, Math.max(12, 6 + 3 * this.modalTiles.length)) : 8;
    height = button !== undefined ? 8 : 4;
    result = Game.modal({x: width, y: height}, button);
    var shrine = Game.attempt.player.level.shrine &&
        Game.attempt.player._x == Game.attempt.player.level.shrine.x &&
        Game.attempt.player._y == Game.attempt.player.level.shrine.y ?
            shrines[Game.attempt.player.level.shrine.type] : null;
    var bestLetterValue =
        this.modalTiles.map(function(c) { return Game.values[c.corpse.glyph]; }).reduce(function(a,b) { return Math.max(a,b); });
    var bestLetterIndex = this.modalTiles.findIndex(function(c) { return Game.values[c.corpse.glyph] == bestLetterValue; });
    for (var i = 0; i < this.modalTiles.length; i++) {
      var padding = this.modalTiles.length == 1 ? 1 : 0;
      drawRackTile(
          Game.display,
          result.center.x - width/2 + 3 * i + 4 + padding,
          result.center.y - height/4,
          this.modalTiles[i].corpse.glyph, this.modalTiles[i].color,
          shrine && (shrine.isWholeWord || i == bestLetterIndex) ? shrine : null);
    }
    Game.drawRackTiles(
        Game.attempt.player.level.map[Game.attempt.player._x + ',' + Game.attempt.player._y].corpse);
    if (Game.attempt.mode.length > 0)
      Game.attempt.mode.pop();
    Game.attempt.mode.push(function(player, index, tiles, closeButton, actButton) {
        return function(loc) {
          var clicked = false, cancelled = false;
          for (var i = 0; i < closeButton.length; i++)
            if (loc.x == closeButton[i].x && loc.y == closeButton[i].y) {
              for (var j = 0; j < tiles.length; j++) {
                if (tiles[j].fromRack)
                  Game.attempt.rack.push(tiles[j].corpse);
                else
                  map[Game.attempt.player._x + ',' + Game.attempt.player._y].corpse = tiles[j].corpse;
              }
              tiles.length = 0;
              clicked = true;
              cancelled = true;
              break;
            }
          if (!clicked && actButton !== undefined)
            for (var i = 0; i < actButton.length; i++)
              if (loc.x == actButton[i].x && loc.y == actButton[i].y) {
                var corpseUnderfoot = map[Game.attempt.player._x + ',' + Game.attempt.player._y].corpse;
                if (tiles.length == 1) {
                  var name = Game.glyphKind[tiles[0].corpse.glyph].name;
                  if (tiles[0].corpse.rotted())
                    Game.attempt.message('You drop the ' + name + ' corpse and it immediately rots to nothing.');
                  else {
                    dropCorpse(map, Game.attempt.player._x, Game.attempt.player._y,
                               [Game.attempt.player.level.up, Game.attempt.player.level.down],
                               tiles[0].corpse);
                    if (tiles[0].corpse.rotting())
                      Game.attempt.message('You drop the ' + name + ' corpse, which begins to rot.');
                    else
                      Game.attempt.message('You drop the ' + name + ' corpse.');
                  }
                } else {
                  var spell = '';
                  for (var j = 0; j < tiles.length; j++)
                    spell += tiles[j].corpse.glyph;
                  var word = checkWord(spell),
                      shrine = Game.attempt.player.level.shrine &&
                          Game.attempt.player._x == Game.attempt.player.level.shrine.x &&
                          Game.attempt.player._y == Game.attempt.player.level.shrine.y ?
                              Game.attempt.player.level.shrine.type : null;
                  if (shrine)
                    Game.attempt.player.level.shrine = null;
                  if (word) {
                    var previousExperience = player.experience,
                        score = spellValue(spell, shrine);
                    player.experience += score;
                    Game.attempt.spellsCast.push({ tiles: spell, depth: player.level.depth, shrine: shrine });
                    Game.attempt.message('You cast ' + formatCastSpell(word, shrine) + '. %c{' + levelIcon.color + '}+' + score + levelIcon.glyph);
                    if (experienceLevelDefinitions.indexOf(levelFor(player.experience)) >
                        experienceLevelDefinitions.indexOf(levelFor(previousExperience)))
                      Game.attempt.message('You have improved.');
                    if (word == 'yendor') {
                      Game.completeGame(Game.attempt, true);
                      Game.showGameSummary(Game.attempt);
                    }
                  }
                  else
                    Game.attempt.message('Nothing happens.');
                }
                tiles.length = 0;
                clicked = true;
                if (corpseUnderfoot)
                  takeCorpse(map, Game.attempt.player._x, Game.attempt.player._y, Game.attempt);
                break;
              }
          if (clicked) {
            Game.attempt.mode.pop();
            if (cancelled)
              Game._drawWholeMap(Game.attempt);
            else {
              Game.save();
              Game.attempt.player.acting = false;
              Game.engine.unlock();
            }
          }
        }; }(this, index, this.modalTiles, result.closeButton, result.actButton));
  }
}

var screenLocToTile = function(loc, player) {
  var display = Game.display.getOptions(),
      x = player.getX() - display.width/2 + loc.x,
      y = player.getY() - display.height/2 + loc.y;
  return { x: x, y: y };
}

var distance = function(a, b) {
  return { dx: a.x - b.x, dy: a.y - b.y };
}

Player.prototype.click = function(loc, tileLoc) {
  if (loc.y == 24) {
    var rackTileIndex = Game.rackTileLocations[loc.x];
    if (rackTileIndex !== undefined)
      this.clickRackTile(rackTileIndex, this.level.map);
    return false;
  } else if (Game.attempt.mode.length > 0) {
    Game.attempt.mode[Game.attempt.mode.length-1](loc);
    return false;
  } else if (tileLoc) {
    var tile = this.level.map[tileLoc.x + ',' + tileLoc.y];
    if (tile.revealed) {
      var playerLoc = { x: this._x, y: this._y },
          path = pathTo(this.level, playerLoc, tileLoc),
          nextLoc = path.length > 0 ? { x: path[0][0], y: path[0][1] } : null,
          nextTile = nextLoc != null ? this.level.map[nextLoc.x + ',' + nextLoc.y] : null;
      if (nextTile !== null && nextTile.contents !== null)
        dealDamage(Game.attempt, nextLoc, nextTile.contents);
      else {
        this.path = path;
        walkPath(Game.attempt);
      }
      return true;
    }
  }
}

var clientDimensionToTile = function(
    playerLoc, displaySize, clientLoc, clientBound, canvasSize, clientSize, spacing) {
  return playerLoc - (displaySize/2) +
      Math.floor((clientLoc - clientBound) * canvasSize / clientSize / spacing);
}

var clientCoords = function(e) {
  return e.changedTouches ?
      { x: e.changedTouches[0].clientX,
        y: e.changedTouches[0].clientY } :
      { x: e.clientX,
        y: e.clientY };
}

var dist2 = function(a, b) {
  var dist = distance(a, b);
  return dist.dx * dist.dx + dist.dy * dist.dy;
}

var tileDimensionToClient = function(
    playerLoc, displaySize, tileLoc, clientBound, canvasSize, clientSize, spacing) {
  return (tileLoc + 0.5 - playerLoc + displaySize/2) * spacing * clientSize / canvasSize + clientBound;
}

var tileCoordsToClient = function(tileLoc, player, display) {
  var displayOptions = display.getOptions(),
      canvas = display._context.canvas,
      clientBounds = canvas.getBoundingClientRect();
  return {
      x: tileDimensionToClient(player._x, displayOptions.width, tileLoc.x,
                               clientBounds.left, canvas.width, canvas.clientWidth,
                               display._backend._spacingX),
      y: tileDimensionToClient(player._y, displayOptions.height, tileLoc.y,
                               clientBounds.top, canvas.height, canvas.clientHeight,
                               display._backend._spacingY) };
}

var getTileClickCoords = function(clientLoc, player, display) {
  var displayOptions = display.getOptions(),
      canvas = display._context.canvas,
      clientBounds = canvas.getBoundingClientRect(),
      tileLoc = {
          x: clientDimensionToTile(player._x, displayOptions.width, clientLoc.x, 
                                   clientBounds.left, canvas.width, canvas.clientWidth,
                                   display._backend._spacingX),
          y: clientDimensionToTile(player._y, displayOptions.height, clientLoc.y, 
                                   clientBounds.top, canvas.height, canvas.clientHeight,
                                   display._backend._spacingY) },
      map = player.level.map,
      dx, dy, loc, key, candidateClientLoc,
      candidates = [];
  for (dx = -1; dx <= 1; dx++)
    for (dy = -1; dy <= 1; dy++) {
      loc = { x: tileLoc.x + dx,
              y: tileLoc.y + dy },
          key = loc.x + ',' + loc.y;
      if (key in map && map[key].passable) {
        candidateClientLoc = tileCoordsToClient(loc, player, display);
        candidates.push([loc, dist2(clientLoc, candidateClientLoc)]);
      }
    }
  candidates.sort(function(a,b) { return a[1] - b[1]; });
  return candidates.length ? candidates[0][0] : undefined;
}

var useAnyTouches = function(raw) {
  var e = { clientX: raw.clientX,
            clientY: raw.clientY,
            touches: [] },
      i;
  if ('touches' in raw)
    for (i = 0; i < raw.touches.length; i++)
      e.touches.push(raw.touches.item(i));
  if ('changedTouches' in raw)
    for (i = 0; i < raw.changedTouches.length; i++)
      e.touches.push(raw.changedTouches.item(i));
  if (e.touches.length == 0)
    e.touches.push({ clientX: raw.clientX, clientY: raw.clientY });
  raw.preventDefault();
  return e;
}

var handleTitleClick = function(e) {
  var coords, i, j;

  if (e.type == 'keydown') return false;
  coords = Game.display.eventToPosition(useAnyTouches(e));
  if (coords[0] >= 0 && coords[1] >= 0)
    for (i = 0; i < Game.titleButtons.length; i++)
      for (j = 0; j < Game.titleButtons[i].locs.length; j++)
        if (Game.titleButtons[i].locs[j].x == coords[0] &&
            Game.titleButtons[i].locs[j].y == coords[1]) {
          Game.titleButtons[i].action();
          return true;
        }
}

Player.prototype.handleEvent = function(e) {
  if (e.type == 'keydown')
    return this.keyPress(e.keyCode, this.level);
  else {
    var coords = Game.display.eventToPosition(useAnyTouches(e));
    if (coords[0] >= 0 && coords[1] >= 0)
      return this.click({x: coords[0], y: coords[1]},
                        getTileClickCoords(clientCoords(e), this, Game.display));
  }
  return false;
}
    
var Foe = function(kindOrState, level, x, y) {
  if (kindOrState.__proto__ !== FoeKind.prototype) {
    this.restore(kindOrState);
    this.level = level;
  }
  else {
    this.level = level;
    this._x = x;
    this._y = y;
    this.kind = kindOrState;
    this.lastSawPlayer = null;
    this.revealed = false;
    if (this.kind === shapeshifter) {
      this.shiftedKind = this.chooseShiftedKind();
      this.health = this.shiftedKind.health;
    } else
      this.health = this.kind.health;
  }
}

Foe.prototype.restore = function(state) {
  this._x = state.x;
  this._y = state.y;
  this.kind = Game.glyphKind[state.kindGlyph];
  if (this.kind === shapeshifter)
    this.shiftedKind = Game.glyphKind[state.shiftedKindGlyph];
  this.lastSawPlayer = state.lastSawPlayer;
  this.revealed = state.revealed;
  this.health = state.health;
}

Foe.prototype.state = function() {
  var state = {
      x: this._x,
      y: this._y,
      kindGlyph: this.kind.glyph,
      lastSawPlayer: this.lastSawPlayer,
      revealed: this.revealed,
      health: this.health };
  if (this.kind === shapeshifter)
    state.shiftedKindGlyph = this.shiftedKind.glyph;
  return state;
}

Foe.prototype.getSpeed = function() { return this.effectiveKind().speed; }

Foe.prototype.chooseShiftedKind = function() {
  return Game.glyphKind[ROT.RNG.getWeightedValue(foeDistribution(foeKinds, this.level.depth))];
}

Foe.prototype.effectiveKind = function() {
  return this.kind !== shapeshifter ? this.kind : this.shiftedKind;
}

var locationsMatch = function(a, b) {
  var dist = distance(a, b);
  return dist.dx == 0 && dist.dy == 0;
}

var pathTo = function(level, fromLoc, toLoc) {
  // avoid stairs unless they are the origin or destination
  var avoid = [];
  if (!locationsMatch(level.down, fromLoc) && !locationsMatch(level.down, toLoc))
    avoid.push(level.down);
  if (!locationsMatch(level.up, fromLoc) && !locationsMatch(level.up, toLoc))
    avoid.push(level.up);
  var astar = new ROT.Path.AStar(toLoc.x, toLoc.y,
      function(map, avoid) {
        return function(x, y) {
          for (var i = 0; i < avoid.length; i++)
            if (avoid[i].x == x && avoid[i].y == y)
              return false;
          var key = x + ',' + y;
          return key in map && map[key].passable;
        };
      }(level.map, avoid),
      {topology:4});

  var path = [];
  astar.compute(fromLoc.x, fromLoc.y,
      function(x, y) {
        this.push([x, y]);
      }.bind(path));

  path.shift();
  return path;
}

var nextStepTo = function(level, fromLoc, toLoc) {
  var path = pathTo(level, fromLoc, toLoc);
  return path.length > 0 ?
         { x: path[0][0], y: path[0][1] } :
         null;
}

var locInView = function(level, fromLoc, targetLoc) {
  var canSeeTarget = false;
  fovFrom(level, fromLoc, level.radius,
      function(loc) {
        if (loc.x == this.x && loc.y == this.y)
          canSeeTarget = true;
      }.bind(targetLoc));
  return canSeeTarget;
}

Foe.prototype.attack = function(player) {
  var damage = Math.min(
      Math.max(0, Math.floor(ROT.RNG.getNormal(3, 1))),
      player.getHealth());
  player.path.length = 0;
  player.woundedness += damage;
  return damage;
}

Foe.prototype.act = function() {
  var loc = { x: this._x, y: this._y },
      playerLoc = { x: Game.attempt.player._x, y: Game.attempt.player._y },
      inView = locInView(this.level, loc, playerLoc),
      nextLoc = inView || this.lastSawPlayer !== null ?
          nextStepTo(this.level, loc, playerLoc) : null;

  if (inView)
    this.lastSawPlayer = 0;
  else if (this.lastSawPlayer !== null && this.lastSawPlayer < 3)
    this.lastSawPlayer++;
  else
    this.lastSawPlayer = null;

  if (nextLoc == null)
    return;

  if (this.kind === shapeshifter &&
      'hurtSinceShift' in this &&
      this.health < levelFor(Game.attempt.player.experience).meanDamage) {
    var kind = this.chooseShiftedKind();
    if (this.shiftedKind !== kind) {
      Game.attempt.message('The shapeshifter changes form, from ' + this.shiftedKind.name +
          ' to ' + kind.name + ', regenerating its health! %c{green}+' + (kind.health - this.health));
      delete this.hurtSinceShift;
      this.shiftedKind = kind;
      this.health = this.shiftedKind.health;
    }
  }

  if (nextLoc.x == playerLoc.x && nextLoc.y == playerLoc.y) {
    var damage = this.attack(Game.attempt.player);
    if (Game.attempt.player.getHealth() == 0) {
      Game.completeGame(Game.attempt);
      Game.engine.lock();
      Game.showGameSummary(Game.attempt);
    }
    else if (damage > 0)
      Game.attempt.message('The ' + this.effectiveKind().name + ' hits you! %c{' + healthIcon.color + '}-' + damage + healthIcon.glyph);
    else
      Game.attempt.message('The ' + this.effectiveKind().name + ' misses you.');
  } else {
    if (this.level.map[nextLoc.x + ',' + nextLoc.y].contents != null)
      return;
    this.level.map[this._x + ',' + this._y].contents = null;
    this._x = nextLoc.x;
    this._y = nextLoc.y;
    this.level.map[this._x + ',' + this._y].contents = this;
  }
}
