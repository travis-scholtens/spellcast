var FoeKind = function(glyph, name, color, health, value, frequency) {
  this.glyph = glyph;
  this.name = name;
  this.color = color;
  this.health = health;
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

var revealFrom = function(level, fromLoc) {
  fovFrom(level, fromLoc, 7,
      function(loc) {
        this[loc.x + ',' + loc.y].revealed = true;
      }.bind(level.map));
}

var Attempt = function(state) {
  if (state)
    this.restore(state);
  else {
    this.levels = [];
    this.player = null;
    this.rack = [];
    this.spellsCast = [];
  
    for (var i = 0; i < 26; i++) {
      this.levels.push(_generateMap(i+1));
      localStorage['spellyLevelState' + i] = JSON.stringify(this.levelState(i));
    }
    var colorBase = ROT.RNG.getUniformInt(80, 255);
    this.player = new Player(this.levels[0], this.levels[0].up.x + 2, this.levels[0].up.y,
        'rgb(' + colorBase + ',' + (colorBase - 40) + ',' + (colorBase - 80) + ')');
    revealFrom(this.player.level, {x: this.player._x, y: this.player._y});
  }
  this.messages = [];
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
  this.levels = [];
  this.player = new Player(state.player);
  this.rack = state.rack;
  this.spellsCast = state.c.map(function(s) {
    return { tiles: s[0], depth: s[1] };
  });
}

Attempt.prototype.state = function() {
  return { player: this.player.state(), rack: this.rack,
      c: this.spellsCast.map(function(s) {
           return [s.tiles, s.depth];
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
                 tile.corpse = t.c;
               else
                 tile.glyph = t.c;
               tile.revealed = t.r;
               return tile;
             });
  this.levels[i].fov = fov(this.levels[i]);
  this.levels[i].foes = [];
  restoreObject(this.levels[i].foes, state.foeData, Pedro, this.levels[i]);
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
      tileData: objectState(this.levels[i].map,
                            function(t) { return t.glyph !== null; },
                            function(t) {
                              return {
                                c: t.corpse !== null ? t.corpse : t.glyph,
                                r: t.revealed };
                            }),
      foeData: listState(this.levels[i].foes)
      };
  return state;
}

var _generateMap = function(depth) {
  var level = { depth: depth },
      dim = level.depth == 1 ? 24 :
            level.depth == 2 ? 36 :
            level.depth == 3 ? 48 :
            level.depth == 4 ? 56 :
            64;
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

  for (var x = 0; x < level.width; x++)
    for (var y = 0; y < level.height; y++)
      if (isWall(level, x, y))
        level.map[x+','+y].glyph = selectWall(neighborWalls(level, x, y));

  level.fov = fov(level);

  var rooms = digger.getRooms();
  var upRoom = ROT.RNG.getUniformInt(0, rooms.length-1);
  var downRoom = upRoom;
  while (downRoom == upRoom)
    downRoom = ROT.RNG.getUniformInt(0, rooms.length-1);

  var upCenter = rooms[upRoom].getCenter(),
      downCenter = rooms[downRoom].getCenter();
  level.up = {x: upCenter[0] - 1, y: upCenter[1]};
  level.down = {x: downCenter[0], y: downCenter[1]};

  var kinds = foeDistribution(foeKinds, level.depth);
  if (level.depth > 1) {
    var totalTiles = foeKinds.slice(0, level.depth).map(function(k){return k.frequency;}).reduce(function(a,b){return a+b;}),
        blankScale = shapeshifter.frequency / (totalTiles + shapeshifter.frequency);
    kinds[shapeshifter.glyph] = shapeshifter.frequency * blankScale;
  }
  level.foes = [];
  var foeLimits = level.depth == 1 ? [2, 2] :
              level.depth == 2 ? [4, 5] :
              level.depth == 3 ? [6, 9] :
              level.depth == 4 ? [9, 13] :
              [12, 18];
  for (var i = 0; i < ROT.RNG.getUniformInt(foeLimits[0], foeLimits[1]); i++) {
    var loc = this.newBeingLocation(freeCells);
    var foe = new Pedro(Game.glyphKind[ROT.RNG.getWeightedValue(kinds)], level, loc.x, loc.y);
    level.map[loc.x + ',' + loc.y].contents = foe;
    level.foes.push(foe);
  }

  return level;
}

Attempt.prototype.levelBelow = function(level) {
  return this.levels[Math.min(this.levels.length - 1, this.levels.indexOf(level) + 1)];
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

var drawRackTile = function(display, x, y, glyph, color) {
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
  if (digits.length > 0)
    for (var i = 0; i < digits.length; i++)
      display.drawText(ax + 0.75 * i, y,
          rackTileText(String.fromCodePoint(0x2080 + digits[i]), color));
  else
    display.draw(ax + 1, y, '', 'black', color);
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
    default: return '\u25cb';
  }
}

var exposed = function(level, x, y) {
  if (level.map[x + ',' + y].passable)
    return true;
  var ncs = [[-1,-1], [-1,1], [1,-1], [1, 1]];
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

var neighborWalls = function(level, x, y) {
  var directions = 0;
  if (isWall(level, x, y-1)) directions |= Direction.N;
  if (isWall(level, x+1, y)) directions |= Direction.E;
  if (isWall(level, x, y+1)) directions |= Direction.S;
  if (isWall(level, x-1, y)) directions |= Direction.W;
  return directions;
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

var handle = function(e) {
  if (Game.attempt && Game.attempt.player && Game.attempt.player.acting)
    Game.attempt.player.handleEvent(e);
  e.preventDefault();
}

var Game = {
    display: null,
    engine: null,
    scheduler: null,
    attempt: null,
    rack_size: 6,
    rackTileLocations: {},

    init: function() {
        this.display = new ROT.Display({width: 26, height: 26, fontSize: 24, spacing:1});
        var canvas = this.display.getContainer();
        canvas.style.width = '100%';
        canvas.style.height = '100%';
        document.getElementById("main").appendChild(canvas);

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

        var serializedState = localStorage.spellyGameState,
            state = serializedState ? JSON.parse(serializedState) : undefined;
        if (state && state.v !== 1)
          state = null;
        if (state)
          ROT.RNG.setState(state.rng);
        this.newAttempt(state ? state.attempt : undefined);
        this.engine.start();
    },

    save: function() {
      var i = this.attempt.player.level.depth-1,
          t0 = new Date().getTime(),
          gameState = { v: 1,
                        rng: ROT.RNG.getState(),
                        attempt: this.attempt.state() },
          t1 = new Date().getTime(),
          levelState = this.attempt.levelState(i),
          t2 = new Date().getTime(),
          gameStr = JSON.stringify(gameState),
          t3 = new Date().getTime(),
          levelStr = JSON.stringify(levelState),
          t4, t5;
      localStorage['spellyGameState'] = gameStr;
      t4 = new Date().getTime();
      localStorage['spellyLevelState' + i] = levelStr;
      t5 = new Date().getTime();
      this.display.drawText(1, 22, (t1 - t0) + ' ' +
                                   (t2 - t1) + ' ' +
                                   (t3 - t2) + ' ' +
                                   (t4 - t3) + ' ' +
                                   (t5 - t4));
    },

    newAttempt: function(state) {
      this.engine.lock();
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
      this.engine.unlock();

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
      result.closeButton = [{
          x: origin.x + 1,
          y: origin.y + 1 }];
      if (button !== undefined) {
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
            this.display.draw(origin.x + xmax/2 - button.length/2 + x - 1,
                              origin.y + ymax - 4 + y,
                              glyph);
          }
        this.display.drawText(origin.x + xmax/2 - button.length/2,
                              origin.y + ymax - 3,
                              button);
        result.actButton = [];
        for (x = 0; x < button.length; x++)
          result.actButton.push({x: origin.x + xmax/2 - button.length/2 + x,
                                 y: origin.y + ymax - 3});
      }
      return result;
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
      var messages = wrapMessages(attempt.messages, display.width);
      var messageCount = messages.length;
      for (var i = 0; i < messageCount; i++) {
        for (var y = 0; y < display.width; y++)
          this.display.draw(y, i + 1, '');
        this.display.drawText(messages[i][0] === ' ' ? 2 : 0, i + 1, messages[i]);
      }
      attempt.messages.length = 0;
      for (var dx = 1; dx < display.width - 1; dx++)
        for (var dy = messageCount + 1; dy < display.height - 3; dy++) {
          var x = attempt.player.getX() - display.width/2 + dx,
              y = attempt.player.getY() - display.height/2 + dy,
              glyph = '',
              color = 'black';
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
                  glyph = tile.corpse == '.' ? '\u25a1' : tile.corpse;
                  color = 'darkgray';
                } else if (x == level.up.x && y == level.up.y)
                  glyph = '\u261d';
                else if (x == level.down.x && y == level.down.y)
                  glyph = '\u261f';
                else
                  glyph = tile.glyph;
              }
            }
          }
          this.display.draw(dx, dy, glyph, color);
        }
      var meterTop = messageCount + 1,
          meterHeight = display.height - 3 - messageCount - 2;
      drawMeter(this.display, 0, meterTop, meterHeight,
                0, 50, 50 - attempt.player.woundedness,
                '\u2625', 'lightpink');
      var expLevelIndex = experienceLevelDefinitions.indexOf(levelFor(attempt.player.experience));
      drawMeter(this.display, display.width - 1, meterTop, meterHeight,
                experienceLevelDefinitions[expLevelIndex-1].expThreshold,
                experienceLevelDefinitions[expLevelIndex].expThreshold,
                attempt.player.experience,
                '\u2638', 'lightgreen');
      this.drawRackTiles(level.map[attempt.player._x + ',' + attempt.player._y].corpse);
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
            this.display, 2 + 3 * i, display.height - 2, this.attempt.rack[i], 'tan');
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
            this.display, 23, display.height - 2, corpse, 'gray');
        for (var j = 0; j < 2; j++)
          this.rackTileLocations[23 + j] = 6;
      }
    }
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
      tile = loc != null ? map[loc[0] + ',' + loc[1]] : { passable: false };
  if (tile.passable && tile.revealed && tile.contents === null) {
    player._x = loc[0];
    player._y = loc[1];
    if (takeCorpse(map, player._x, player._y, attempt))
      path.length = 0;
    if (useStairs(attempt))
      path.length = 0;
    revealFrom(player.level, {x: player._x, y: player._y});
    Game._drawWholeMap(attempt);
    return true;
  }
  return false;
}

Player.prototype.act = function() {
  if (this.path.length > 0 && walkPath(Game.attempt)) {
    var done = null,
        promise = { then: function(cb) { done = cb; } };
    setTimeout( function() {
      Game._drawWholeMap(Game.attempt);
      done(); }, 10);
    return promise;
  } else {
    Game.save();
    Game.engine.lock();
    this.acting = true;
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
  if (tile.corpse != null && attempt.rack.length < Game.rack_size) {
     var name = Game.glyphKind[tile.corpse].name;
     attempt.rack.push(tile.corpse);
     tile.corpse = null;
     Game.attempt.message('You take the ' + name + ' corpse.');
     return true;
  }
  return false;
}

dropCorpse = function(map, x, y, corpse) {
  var q = [[x,y]];
  var seen = {};
  while (q.length > 0) {
    var u = q.shift();
    var key = u[0] + ',' + u[1];
    if (map[key].passable && map[key].corpse == null) {
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
      stairLoc;
  if (player._x == level.down.x && player._y == level.down.y) {
    Game.save();
    player.level = attempt.levelBelow(level);
    stairLoc = attempt.player.level.up;
  }
  else if (player._x == level.up.x && player._y == level.up.y) {
    Game.save();
    player.level = attempt.levelAbove(level);
    stairLoc = player.level.down;
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
    dropCorpse(map, loc.x, loc.y, foe.kind.glyph);
    Game.scheduler.remove(foe);
    foes.splice(foes.indexOf(foe), 1);
    attempt.message('You kill the ' + name + '!');
  }
  else if (damage > 0)
    attempt.message('You hit the ' + name + '! %c{red}(-' + damage + ')');
  else
    attempt.message('You miss the ' + name + '.');
}

Player.prototype.keyPress = function(code, level) {
    if (Game.attempt.mode.length > 0) return;

    var keyMap = {};
    keyMap[38] = 0;
    keyMap[33] = 1;
    keyMap[39] = 2;
    keyMap[34] = 3;
    keyMap[40] = 4;
    keyMap[35] = 5;
    keyMap[37] = 6;
    keyMap[36] = 7;

    /* one of numpad directions? */
    if (!(code in keyMap)) { return; }

    /* is there a free space? */
    var dir = ROT.DIRS[8][keyMap[code]];
    var newX = this._x + dir[0];
    var newY = this._y + dir[1];
    var newKey = newX + "," + newY;
    if (!(newKey in level.map && level.map[newKey].passable))
      return;

    var contents = level.map[newKey].contents;
    if (contents != null)
      dealDamage(Game.attempt, { x: newX, y: newY }, contents);
    else {
      this._x = newX;
      this._y = newY;
      takeCorpse(level.map, this._x, this._y, Game.attempt);
      useStairs(Game.attempt);
      revealFrom(this.level, {x: this._x, y: this._y});
    }
    this.acting = false;
    Game.engine.unlock();
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
      word in words_am || word in words_nz ? word : false;
}

Player.prototype.clickRackTile = function(index, map) {
  var result,
      button,
      width,
      height;
  if (index < Game.attempt.rack.length) {
    var remaining = Game.attempt.rack.splice(index);
    this.modalTiles.push({glyph: remaining.shift(), fromRack: true, color: 'tan'});
    Game.attempt.rack = Game.attempt.rack.concat(remaining);
  } else if (index == 6) {
    var key = Game.attempt.player._x + ',' + Game.attempt.player._y
    var glyph = map[key].corpse;
    if (glyph != null) {
      this.modalTiles.push({glyph: glyph, fromRack: false, color: 'gray'});
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
    for (var i = 0; i < this.modalTiles.length; i++) {
      var padding = this.modalTiles.length == 1 ? 1 : 0;
      drawRackTile(
          Game.display,
          result.center.x - width/2 + 3 * i + 4 + padding,
          result.center.y - height/4,
          this.modalTiles[i].glyph, this.modalTiles[i].color);
    }
    Game.drawRackTiles(
        Game.attempt.player.level.map[Game.attempt.player._x + ',' + Game.attempt.player._y].corpse);
    if (Game.attempt.mode.length > 0)
      Game.attempt.mode.pop();
    Game.attempt.mode.push(function(player, index, tiles, closeButton, actButton) {
        return function(loc) {
          var clicked = false;
          for (var i = 0; i < closeButton.length; i++)
            if (loc.x == closeButton[i].x && loc.y == closeButton[i].y) {
              for (var j = 0; j < tiles.length; j++) {
                if (tiles[j].fromRack)
                  Game.attempt.rack.push(tiles[j].glyph);
                else
                  map[Game.attempt.player._x + ',' + Game.attempt.player._y].corpse = tiles[j].glyph;
              }
              tiles.length = 0;
              clicked = true;
              break;
            }
          if (!clicked && actButton !== undefined)
            for (var i = 0; i < actButton.length; i++)
              if (loc.x == actButton[i].x && loc.y == actButton[i].y) {
                if (tiles.length == 1) {
                  var name = Game.glyphKind[tiles[0].glyph].name;
                  dropCorpse(map, Game.attempt.player._x, Game.attempt.player._y, tiles[0].glyph);
                  Game.attempt.message('You drop the ' + name + ' corpse.');
                } else {
                  var spell = '', score = 0;
                  for (var j = 0; j < tiles.length; j++) {
                    spell += tiles[j].glyph;
                    score += Game.values[tiles[j].glyph];
                  }
                  if (spell.length == 7)
                    score += 50;
                  var word = checkWord(spell);
                  if (word) {
                    var previousExperience = player.experience;
                    player.experience += score;
                    Game.attempt.spellsCast.push({ tiles: spell, depth: player.level.depth });
                    Game.attempt.message('You cast ' + word.toUpperCase() + '. %c{green}(+' + score + ')');
                    if (experienceLevelDefinitions.indexOf(levelFor(player.experience)) >
                        experienceLevelDefinitions.indexOf(levelFor(previousExperience))) {
                      player.woundedness = 0;
                      Game.attempt.message('You have improved.');
                    }
                  }
                  else
                    Game.attempt.message('Nothing happens.');
                }
                tiles.length = 0;
                clicked = true;
                break;
              }
          if (clicked) {
            Game.attempt.mode.pop();
            Game.save();
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
  } else if (Game.attempt.mode.length > 0)
    Game.attempt.mode[Game.attempt.mode.length-1](loc);
  else if (tileLoc) {
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
      this.acting = false;
      Game.engine.unlock();
    }
  }
}

ROT.Display.prototype.eventToClientPosition = function(clientLoc) {
  var x = clientLoc.x,
      y = clientLoc.y;

  var rect = this._context.canvas.getBoundingClientRect();
  x -= rect.left;
  y -= rect.top;

  x *= this._context.canvas.width / this._context.canvas.clientWidth;
  y *= this._context.canvas.height / this._context.canvas.clientHeight;

  if (x < 0 || y < 0 || x >= this._context.canvas.width || y >= this._context.canvas.height)
    return [-1, -1];

  return this._backend.eventToPosition(x, y);
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

Player.prototype.handleEvent = function(e) {
  if (e.type == 'keydown')
    this.keyPress(e.keyCode, this.level);
  else {
    var clientLoc = clientCoords(e);
    var coords = Game.display.eventToClientPosition(clientLoc);
    if (coords[0] >= 0 && coords[1] >= 0)
      this.click({x: coords[0], y: coords[1]},
                 getTileClickCoords(clientLoc, this, Game.display));
  }
  if (Game.attempt.mode.length == 0)
    Game._drawWholeMap(Game.attempt);
}
    
var Pedro = function(kindOrState, level, x, y) {
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

Pedro.prototype.restore = function(state) {
  this._x = state.x;
  this._y = state.y;
  this.kind = Game.glyphKind[state.kindGlyph];
  if (this.kind === shapeshifter)
    this.shiftedKind = Game.glyphKind[state.shiftedKindGlyph];
  this.lastSawPlayer = state.lastSawPlayer;
  this.revealed = state.revealed;
  this.health = state.health;
}

Pedro.prototype.state = function() {
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

Pedro.prototype.getSpeed = function() { return 100; }

Pedro.prototype.chooseShiftedKind = function() {
  return Game.glyphKind[ROT.RNG.getWeightedValue(foeDistribution(foeKinds, this.level.depth))];
}

Pedro.prototype.effectiveKind = function() {
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

Pedro.prototype.attack = function(player) {
  var damage = Math.min(
      Math.max(0, Math.floor(ROT.RNG.getNormal(3, 1))),
      player.getHealth());
  player.path.length = 0;
  player.woundedness += damage;
  return damage;
}

Pedro.prototype.act = function() {
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
      this.health < levelFor(Game.attempt.player.experience).meanDamage) {
    var kind = this.chooseShiftedKind();
    if (this.shiftedKind !== kind) {
      Game.attempt.message('The shapeshifter changes form, from ' + this.shiftedKind.name +
          ' to ' + kind.name + ', regenerating its health! %c{green}(+' + (kind.health - this.health) + ')');
      this.shiftedKind = kind;
      this.health = this.shiftedKind.health;
    }
  }

  if (nextLoc.x == playerLoc.x && nextLoc.y == playerLoc.y) {
    var damage = this.attack(Game.attempt.player);
    if (Game.attempt.player.getHealth() == 0) {
      Game.attempt.message('The ' + this.effectiveKind().name + ' kills you!');
      Game.newAttempt();
    }
    else if (damage > 0)
      Game.attempt.message('The ' + this.effectiveKind().name + ' hits you! %c{red}(-' + damage + ')');
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
