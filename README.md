# Minecraft Micro Edition

Un juego de mundo abierto estilo Minecraft en 3D para J2ME (CLDC 1.1 / MIDP 2.0). Cuenta con mundo infinito, supervivencia, crafteo, generación procedural y mundos guardables. Todo el renderizado se realiza por software (polígonos y texturas procedurales dibujadas en tiempo real), optimizado para consumir la menor cantidad de memoria RAM posible.

> **Aviso:** Se probó exhaustivamente en **J2ME Loader** (Android). En dispositivos físicos muy antiguos puede requerir ajustar la distancia de renderizado al mínimo.

## Novedades de la versión 1.1.0
- **Texturas e Iconos:** Se activó el renderizado de texturas procedurales para los bloques (vetas de madera, piedra, menas) y dibujos píxel-art para los iconos del inventario (herramientas, comidas, etc.), todo sin usar archivos de imagen para ahorrar memoria.
- **Contenido ampliado:** Se agregaron biomas (desiertos, nieve), agua, minerales (carbón, hierro, oro, diamante), nuevas herramientas (piedra, hierro, oro, diamante), puertas funcionales, camas para dormir y cambiar el punto de reaparición, y fundición de minerales en el horno.

## Características Principales

**Mundo y Generación**
- Mundo infinito por chunks (16x16, altura 32) con relieve, ríos y biomas.
- Ciclo de día y noche de 20 minutos, con iluminación dinámica por antorchas.
- Generación de árboles, cactus, menas (carbón, hierro, oro, diamante) y cuevas.
- Bloques disponibles: pasto, tierra, piedra, adoquín, arena, arenisca, grava, nieve, hielo, agua, troncos, hojas, tablones, mesa de crafteo, horno, antorcha, brote, TNT, puertas y camas.

**Jugador y Supervivencia**
- Sistema de vida, hambre, regeneración, inanición y daño por caída, cactus y ahogamiento (burbujas de oxígeno).
- Autosalto para subir escalones de un bloque automáticamente.
- Combate cuerpo a cuerpo con espadas y herramientas.
- Interacción con el entorno: abrir/cerrar puertas, dormir en camas para saltar la noche y fijar el punto de reaparición.

**Crafteo y Progresión**
- Inventario de 36 casillas + hotbar de 9.
- Crafteo 2x2 en el inventario y 3x3 en la mesa de crafteo.
- Herramientas de 5 materiales distintos (madera, piedra, hierro, oro, diamante), cada uno con su nivel de minería, velocidad y durabilidad.
- Horno completo: funde adoquín en piedra, troncos en carbón, menas en lingotes y carne cruda en cocida.

**Mobs**
- Pasivos: oveja, pollo y vaca.
- Hostiles: zombie y creeper. Aparecen de noche y se queman con la luz del día.

## Controles
Se juega con el teclado numérico (en J2ME Loader, con el teclado virtual).
- **Layout A (caminar):** 2 adelante, 8 atrás, 4 y 6 girar, 7 y 9 mirar arriba y abajo, 5 saltar.
- **Layout B (cámara):** 2 y 8 mirar arriba y abajo, 4 y 6 girar, 5 avanzar, 7 retroceder, 9 saltar.
- **#:** alterna entre los dos layouts.
- **0:** abre o cierra el inventario.
- **1 y 3:** casilla anterior o siguiente de la hotbar.
- **\* mantenido:** minar o atacar. **Toque corto:** poner bloque, comer, abrir mesa, horno o puerta.
- **L o R:** menú de pausa.
- **Inventario:** 2, 4, 6 y 8 mueven el cursor, 5 selecciona y \* toma o deja de a uno.

Más detalle en `working-tree/NOTAS_CONTROLES.md`.

## Compilar
Hace falta `javac`, `jar` y `java`, más una carpeta `j2me-builder-files/` con los stubs `cldcapi11.jar` y `midpapi20.jar` en `lib/` y `proguard.jar` (no se incluye en el repo).

    bash build.sh

El jar final queda en `working-tree/build/hello-final.jar`.

## Descarga
El `.jar` listo para instalar está en la sección de **Releases** de este repositorio.
