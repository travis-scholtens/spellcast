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

var fovFrom = function(level, loc, callback) {
  level.fov.compute(loc.x, loc.y, level.radius,
      function(x, y, _, _) {
        callback({x: x, y: y});
      });
}

var revealFrom = function(level, fromLoc) {
  fovFrom(level, fromLoc,
      function(loc) {
        level.map[loc.x + ',' + loc.y].revealed = true;
      }.bind(level));
}

var Attempt = function() {
  this.levels = [];
  this.player = null;
  this.messages = [];
  this.rack = [];
  this.mode = [];

  for (var i = 0; i < 26; i++)
    this.levels.push(_generateMap(i+1));
  this.player = new Player(this.levels[0], this.levels[0].up.x + 2, this.levels[0].up.y);
  revealFrom(this.player.level, {x: this.player._x, y: this.player._y});

  Game.scheduler.add(this.player, true);
  Game.schedule(this.player.level.foes);
}

var _generateMap = function(depth) {
  var level = {};
  level.width = 64;
  level.height = 64;
  level.radius = Math.sqrt(level.width * level.width + level.height * level.height);
  level.map = {};
  var digger = new ROT.Map.Digger(level.width, level.height);
  var freeCells = [];

  var digCallback = function(x, y, value) {
      var key = x+","+y;
      this.map[key] = new Tile(value ? false : '.');
      if (!value)
        freeCells.push(key);
  }
  digger.create(digCallback.bind(level));

  for (var x = 0; x < level.width; x++)
    for (var y = 0; y < level.height; y++)
      if (isWall(level, x, y))
        level.map[x+','+y].glyph = selectWall(neighborWalls(level, x, y));

  level.fov = new ROT.FOV.PreciseShadowcasting(function(x, y) {
    return x >= 0 && x < this.width && y >= 0 && y < this.height ?
        this.map[x+','+y].passable : false;
  }.bind(level));

  var rooms = digger.getRooms();
  var upRoom = ROT.RNG.getUniformInt(0, rooms.length-1);
  var downRoom = upRoom;
  while (downRoom == upRoom)
    downRoom = ROT.RNG.getUniformInt(0, rooms.length-1);

  var upCenter = rooms[upRoom].getCenter(),
      downCenter = rooms[downRoom].getCenter();
  level.up = {x: upCenter[0] - 1, y: upCenter[1]};
  level.down = {x: downCenter[0], y: downCenter[1]};

  var kinds = foeDistribution(foeKinds, depth);
  level.foes = [];
  for (var i = 0; i < ROT.RNG.getUniformInt(12, 18); i++) {
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
    digits = [value];
  } else {
    ax = x - 0.75;
    digits = [Math.floor(value/10), value % 10];
  }
  display.drawText(ax, y, this.rackTileText(glyph, color));
  for (var i = 0; i < digits.length; i++)
    display.drawText(ax + 0.75 * i, y,
        rackTileText(String.fromCodePoint(0x2080 + digits[i]), color));
}

var drawRackTile = function(display, x, y, glyph, color) {
  var value = Game.values[glyph],
      ax,
      digits;
  if (value < 10) {
    ax = x - 0.5;
    digits = [value];
  } else {
    ax = x - 0.75;
    digits = [Math.floor(value/10), value % 10];
  }
  display.drawText(ax, y, rackTileText(glyph, color));
  for (var i = 0; i < digits.length; i++)
    display.drawText(ax + 0.75 * i, y,
        rackTileText(String.fromCodePoint(0x2080 + digits[i]), color));
}

Attempt.prototype.message = function(text) {
  this.messages.push(text);
}

var Direction = {
  N: 0b0001,
  E: 0b0010,
  S: 0b0100,
  W: 0b1000
}

var selectWall = function(neighborWallDirections) {
  switch (neighborWallDirections) {
    case 0b0011: return '\u255a';
    case 0b0101: return '\u2551';
    case 0b0110: return '\u2554';
    case 0b0111: return '\u2560';
    case 0b1001: return '\u255d';
    case 0b1010: return '\u2550';
    case 0b1011: return '\u2569';
    case 0b1100: return '\u2557';
    case 0b1101: return '\u2563';
    case 0b1110: return '\u2566';
    case 0b1111: return '\u256c';
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

        this.values = {};
        for (var i = 0; i < foeKinds.length; i++)
          this.values[foeKinds[i].glyph] = foeKinds[i].value;

        this.glyphKind = {};
        foeKinds.forEach(
            function(k) {
              this[k.glyph] = k;
            }.bind(this.glyphKind));

        this.scheduler = new ROT.Scheduler.Speed();

        this.engine = new ROT.Engine(this.scheduler);

        this.newAttempt();
        this.engine.start();
    },

    newAttempt: function() {
      this.engine.lock();
      this.scheduler.clear();
      this.attempt = new Attempt();
      this._drawWholeMap(this.attempt);
      this.engine.unlock();
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
                                y == 1 ? '\u2573' :
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
      var messageCount = attempt.messages.length;
      for (var i = 0; i < messageCount; i++)
        this.display.drawText(0, i, '> ' + attempt.messages[i]);
      attempt.messages.length = 0;
      for (var dx = 1; dx < display.width - 1; dx++)
        for (var dy = messageCount; dy < display.height - 3; dy++) {
          var x = attempt.player.getX() - display.width/2 + dx,
              y = attempt.player.getY() - display.height/2 + dy;
          if ((0 <= x && x < level.width) &&
              (0 <= y && y < level.height)) {
            if (x == attempt.player._x && y == attempt.player._y)
              this.display.draw(dx, dy, '@', '#ff0');
            else {
              var tile = level.map[x + ',' + y];
              var glyph = '', color = 'white';
              if (tile.revealed) {
                if (tile.contents != null) {
                  glyph = tile.contents.kind.glyph;
                  color = tile.contents.kind.color;
                } else if (tile.corpse != null) {
                  glyph = tile.corpse;
                  color = 'darkgray';
                } else if (x == level.up.x && y == level.up.y)
                  glyph = '\u261d';
                else if (x == level.down.x && y == level.down.y)
                  glyph = '\u261f';
                else
                  glyph = tile.glyph;
              }
              this.display.draw(dx, dy, glyph, color);
            }
          }
        }
      var meterTop = messageCount,
          meterHeight = display.height - 3 - messageCount - 1;
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

var Player = function(level, x, y) {
    this.level = level;
    this._x = x;
    this._y = y;
    this.lastClickEvent = 0;
    this.modalTiles = [];
    this.experience = 0;
    this.woundedness = 0;
}

Player.prototype.getSpeed = function() {
  return levelFor(this.experience).speed;
}

Player.prototype.getHealth = function() {
  return 50 - this.woundedness;
}
    
Player.prototype.getX = function() { return this._x; }
Player.prototype.getY = function() { return this._y; }

Player.prototype.act = function() {
    Game.engine.lock();
    window.addEventListener("keydown", this);
    window.addEventListener("mouseup", this);
    window.addEventListener("touchstart", this);
}

Player.prototype.attack = function(foe) {
  var damage = Math.min(
      Math.max(0, Math.floor(ROT.RNG.getNormal(levelFor(this.experience).meanDamage, 2))),
      foe.health);
  foe.health -= damage;
  return damage;
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
    if (contents != null) {
      var damage = this.attack(contents);
      var name = Game.glyphKind[contents.kind.glyph].name;
      if (contents.health == 0) {
        level.map[newKey].contents = null;
        dropCorpse(level.map, newX, newY, contents.kind.glyph);
        Game.scheduler.remove(contents);
        Game.attempt.message('You kill the ' + name + '!');
      }
      else if (damage > 0)
        Game.attempt.message('You hit the ' + name + '! %c{red}(-' + damage + ')');
      else
        Game.attempt.message('You miss the ' + name + '.');
    }
    else {
      this._x = newX;
      this._y = newY;
      var key = this._x + ',' + this._y;
      if (level.map[key].corpse != null && Game.attempt.rack.length < Game.rack_size) {
        var name = Game.glyphKind[level.map[key].corpse].name;
        Game.attempt.rack.push(level.map[key].corpse);
        level.map[key].corpse = null;
        Game.attempt.message('You take the ' + name + ' corpse.');
      }
      var stairLoc;
      if (this._x == level.down.x && this._y == level.down.y) {
        this.level = Game.attempt.levelBelow(level);
        stairLoc = this.level.up;
      }
      else if (this._x == level.up.x && this._y == level.up.y) {
        this.level = Game.attempt.levelAbove(level);
        stairLoc = this.level.down;
      }
      if (this.level != level) {
        Game.unschedule(level.foes);
        Game.schedule(this.level.foes);
        this._x = stairLoc.x;
        this._y = stairLoc.y;
      }
      revealFrom(this.level, {x: this._x, y: this._y});
    }
    window.removeEventListener("keydown", this);
    window.removeEventListener("mouseup", this);
    window.removeEventListener("touchstart", this);
    Game.engine.unlock();
}

var levelFor = function(experience) {
  var i;
  for (i = 0; i < experienceLevelDefinitions.length; i++)
    if (experience < experienceLevelDefinitions[i].expThreshold)
      return experienceLevelDefinitions[i];
  return experienceLevelDefinitions[experienceLevelDefinitions.length-1];
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
    Game.drawRackTiles();
    if (Game.attempt.mode.length > 0)
      Game.attempt.mode.pop();
    Game.attempt.mode.push(function(player, index, tiles, closeButton, actButton) {
        return function(loc) {
          var clicked = false;
          for (var i = 0; i < closeButton.length; i++)
            if (loc.x == closeButton[i].x && loc.y == closeButton[i].y) {
              for (var j = 0; j < tiles.length; j++) {
                if (tiles[j].fromRack)
                  Game.rack.push(tiles[j].glyph);
                else
                  map[Game.player._x + ',' + Game.player._y].corpse = tiles[j].glyph;
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
                  if (spell in words) {
                    var previousExperience = player.experience;
                    player.experience += score;
                    Game.attempt.message('You cast ' + spell.toUpperCase() + '. %c{green}(+' + score + ')');
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
          if (clicked)
            Game.attempt.mode.pop();
        }; }(this, index, this.modalTiles, result.closeButton, result.actButton));
  }
}

Player.prototype.click = function(loc) {
  if (loc.y == 24) {
    var rackTileIndex = Game.rackTileLocations[loc.x];
    if (rackTileIndex !== undefined)
      this.clickRackTile(rackTileIndex, this.level.map);
  } else if (Game.attempt.mode.length > 0)
    Game.attempt.mode[Game.attempt.mode.length-1](loc);
}

ROT.Display.prototype.eventToClientPosition = function(e) {
  if (e.touches) {
    var x = e.touches[0].clientX;
    var y = e.touches[0].clientY;
  } else {
    var x = e.clientX;
    var y = e.clientY;
  }

  var rect = this._context.canvas.getBoundingClientRect();
  x -= rect.left;
  y -= rect.top;

  x *= this._context.canvas.width / this._context.canvas.clientWidth;
  y *= this._context.canvas.height / this._context.canvas.clientHeight;

  if (x < 0 || y < 0 || x >= this._context.canvas.width || y >= this._context.canvas.height)
    return [-1, -1];

  return this._backend.eventToPosition(x, y);
}

Player.prototype.handleEvent = function(e) {
  if (e.type == 'keydown')
    this.keyPress(e.keyCode, this.level);
  else {
    var now = new Date().getTime();
    if (now - this.lastClickEvent < 1000)
      return;
    this.lastClickEvent = now;
    var coords = Game.display.eventToClientPosition(e);
    if (coords[0] >= 0 && coords[1] >= 0)
      this.click({x: coords[0], y: coords[1]});
  }
  if (Game.attempt.mode.length == 0)
    Game._drawWholeMap(Game.attempt);
}
    
var Pedro = function(kind, level, x, y) {
    this.level = level;
    this._x = x;
    this._y = y;
    this.kind = kind;
    this.health = kind.health;
    this.lastSawPlayer = null;
}

Pedro.prototype.getSpeed = function() { return 100; }

var pathTo = function(map, fromLoc, toLoc) {
  var astar = new ROT.Path.AStar(toLoc.x, toLoc.y,
      function(x, y) {
        var key = x + ',' + y;
        return key in this && this[key].passable;
      }.bind(map),
      {topology:4});

  var path = [];
  astar.compute(fromLoc.x, fromLoc.y,
      function(x, y) {
        this.push([x, y]);
      }.bind(path));

  path.shift();
  return path.length > 0 ?
         { x: path[0][0], y: path[0][1] } :
         null;
}

var locInView = function(level, fromLoc, targetLoc) {
  var canSeeTarget = false;
  fovFrom(level, fromLoc,
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
  player.woundedness += damage;
  return damage;
}

Pedro.prototype.act = function() {
  var loc = { x: this._x, y: this._y },
      playerLoc = { x: Game.attempt.player._x, y: Game.attempt.player._y },
      inView = locInView(this.level, loc, playerLoc),
      nextLoc = inView || this.lastSawPlayer !== null ?
          pathTo(this.level.map, loc, playerLoc) : null;

  if (inView)
    this.lastSawPlayer = 0;
  else if (this.lastSawPlayer !== null && this.lastSawPlayer < 3)
    this.lastSawPlayer++;
  else
    this.lastSawPlayer = null;

  if (nextLoc == null)
    return;

  if (nextLoc.x == playerLoc.x && nextLoc.y == playerLoc.y) {
    var damage = this.attack(Game.attempt.player);
    if (Game.attempt.player.getHealth() == 0) {
      Game.attempt.message('The ' + this.kind.name + ' kills you!');
      Game.newAttempt();
    }
    else if (damage > 0)
      Game.attempt.message('The ' + this.kind.name + ' hits you! %c{red}(-' + damage + ')');
    else
      Game.attempt.message('The ' + this.kind.name + ' misses you.');
  } else {
    if (this.level.map[nextLoc.x + ',' + nextLoc.y].contents != null)
      return;
    this.level.map[this._x + ',' + this._y].contents = null;
    this._x = nextLoc.x;
    this._y = nextLoc.y;
    this.level.map[this._x + ',' + this._y].contents = this;
  }
}
