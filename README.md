# Minecraft Micro Edition

Un Minecraft en 3D para J2ME (CLDC 1.1 / MIDP 2.0), con mundo infinito, supervivencia y mundos guardables. Todo el render es por software (polígonos rellenos, sin texturas), pensado para correr en un emulador de J2ME en Android.

> **Aviso: es experimental.** Se probó en **J2ME Loader**. En dispositivos reales (teléfonos con Java ME de verdad) puede no funcionar, ir lento o quedarse sin memoria.

## Qué está implementado

**Menús y mundos**
- Menú principal estilo Pocket Edition: Jugar y Salir.
- Pantalla de mundos: lista para cargar, botón para crear y opción de borrar.
- Crear mundo con nombre y semilla (vacía = aleatoria).
- Guardado en RecordStore: autoguardado cada 2 minutos y al volver al menú. Guarda posición, inventario, vida, hambre, hora del día y el horno.
- Menú de pausa con L o R: Reanudar, Ajustes y Volver al menú principal.
- Ajustes: distancia de renderizado (Mínima, Normal, Lejos, Muy lejos, Extrema).

**Mundo**
- Mundo infinito por chunks de 16x16 (altura 32) con relieve generado por ruido.
- Bloques: pasto, tierra, piedra, tronco, hojas, tablones, mesa de crafteo, adoquín, horno, brote y antorcha.
- Árboles generados.
- Ciclo día/noche de 20 minutos: el cielo y los bloques se oscurecen de noche.

**Jugador**
- Vida y hambre, con regeneración, inanición y daño por caída.
- Autosalto: sube escalones de un bloque al caminar contra ellos.
- Minar y colocar bloques; el tiempo de minado depende de la herramienta correcta.
- Herramientas de madera: pico, hacha, pala y espada, con durabilidad.
- Comer: manzana y carnes (crudas y cocidas).

**Crafteo**
- Inventario de 36 casillas más hotbar de 9.
- Crafteo 2x2 en el inventario y 3x3 en la mesa de crafteo.
- Recetas: tablones, palos, mesa, herramientas de madera, horno (8 adoquines en anillo) y antorchas (carbón vegetal + palo).

**Horno y cocina**
- Funde adoquín en piedra, troncos en carbón vegetal y carne cruda en carne cocida.
- Combustible: troncos y tablones (15 s), palos (5 s) y carbón vegetal (80 s). Cada item tarda 10 s.

**Mobs**
- Pasivos: oveja, pollo y vaca, con sus drops (lana, carne, plumas).
- Hostiles: zombie (persigue y golpea) y creeper (se acerca, parpadea y explota rompiendo bloques). Salen de noche y se queman de día.

**Plantación**
- Las hojas pueden soltar brotes. Se plantan sobre pasto o tierra y se convierten en árbol a los 5 ticks exactos, si hay espacio.

**Iluminación**
- Las antorchas iluminan el entorno de noche.

## Qué no está implementado
- Sonido y música.
- Texturas (todo son colores planos).
- Minerales, herramientas de piedra o hierro, armaduras.
- Agua, lava, arena y vidrio.
- Camas, clima y otras dimensiones.
- Multijugador.
- Usos para la pólvora o la carne podrida (se obtienen pero no tienen receta).
- Un horno por bloque: hay uno por mundo, compartido por todos los hornos colocados.
- Guardar los mobs: se regeneran al entrar al mundo.
- Los brotes pierden su temporizador si salís del mundo antes de que crezcan.

## Controles
Se juega con teclado numérico (en J2ME Loader, con el teclado virtual).

- **Layout A (caminar):** 2 adelante, 8 atrás, 4 y 6 girar, 7 y 9 mirar arriba y abajo, 5 saltar.
- **Layout B (cámara):** 2 y 8 mirar arriba y abajo, 4 y 6 girar, 5 avanzar, 7 retroceder, 9 saltar.
- **#:** alterna entre los dos layouts.
- **0:** abre o cierra el inventario.
- **1 y 3:** casilla anterior o siguiente de la hotbar.
- **\*** mantenido: minar o atacar. Toque corto: poner bloque, comer, abrir mesa u horno.
- **L o R:** menú de pausa.
- **Inventario:** 2, 4, 6 y 8 mueven el cursor, 5 selecciona y \* toma o deja de a uno.

Más detalle en `working-tree/NOTAS_CONTROLES.md`.

## Compilar
Hace falta `javac`, `jar` y `java`, más una carpeta `j2me-builder-files/` con los stubs `cldcapi11.jar` y `midpapi20.jar` en `lib/` y `proguard.jar` (no se incluye en el repo).

    bash build.sh

El jar final queda en `working-tree/build/hello-final.jar`.

## Descarga
El `.jar` listo está en la sección Releases.
