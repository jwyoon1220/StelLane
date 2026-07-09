document.addEventListener('DOMContentLoaded', () => {
  // --- Header Scroll Effect ---
  const header = document.querySelector('header');
  if (header) {
    window.addEventListener('scroll', () => {
      if (window.scrollY > 50) {
        header.classList.add('scrolled');
      } else {
        header.classList.remove('scrolled');
      }
    });
  }

  // --- Starfield Background Animation ---
  const canvas = document.getElementById('starfield');
  if (canvas) {
    const ctx = canvas.getContext('2d');
    let stars = [];
    const maxStars = 150;
    let animationFrameId;

    function resizeCanvas() {
      canvas.width = window.innerWidth;
      canvas.height = window.innerHeight;
    }

    class Star {
      constructor() {
        this.reset(true);
      }

      reset(init = false) {
        this.x = Math.random() * canvas.width;
        this.y = init ? Math.random() * canvas.height : -10;
        this.size = Math.random() * 2 + 0.5;
        this.speed = Math.random() * 0.8 + 0.2;
        this.alpha = Math.random() * 0.7 + 0.3;
        if (this.size > 2) {
          this.speed *= 1.5;
          this.alpha = 1.0;
        }
        this.color = Math.random() > 0.7 
          ? (Math.random() > 0.5 ? '#ff6b9d' : '#a370f7') 
          : '#ffffff';
      }

      update() {
        this.y += this.speed;
        if (this.y > canvas.height) {
          this.reset();
        }
      }

      draw() {
        ctx.beginPath();
        ctx.arc(this.x, this.y, this.size, 0, Math.PI * 2);
        ctx.fillStyle = this.color;
        ctx.shadowBlur = this.size > 1.8 ? 5 : 0;
        ctx.shadowColor = this.color;
        ctx.globalAlpha = this.alpha;
        ctx.fill();
        ctx.globalAlpha = 1.0;
        ctx.shadowBlur = 0;
      }
    }

    function initStars() {
      stars = [];
      for (let i = 0; i < maxStars; i++) {
        stars.push(new Star());
      }
    }

    function animate() {
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      stars.forEach(star => {
        star.update();
        star.draw();
      });
      animationFrameId = requestAnimationFrame(animate);
    }

    window.addEventListener('resize', () => {
      resizeCanvas();
    });

    resizeCanvas();
    initStars();
    animate();
  }

  // --- Copy to Clipboard functionality ---
  const copyBtn = document.getElementById('btn-copy');
  const codeContent = document.getElementById('code-content');

  if (copyBtn && codeContent) {
    copyBtn.addEventListener('click', () => {
      const textToCopy = codeContent.innerText;
      navigator.clipboard.writeText(textToCopy).then(() => {
        copyBtn.classList.add('copied');
        copyBtn.innerHTML = '<i class="fas fa-check"></i>';
        
        setTimeout(() => {
          copyBtn.classList.remove('copied');
          copyBtn.innerHTML = '<i class="far fa-copy"></i>';
        }, 2000);
      }).catch(err => {
        console.error('Failed to copy code: ', err);
      });
    });
  }

  // --- Command Tab Switching ---
  const tabBtns = document.querySelectorAll('.tab-btn');
  if (tabBtns.length > 0 && codeContent) {
    const commandTexts = {
      play: `./gradlew :app:runGame`,
      build: `./gradlew :app:deploy`,
      builder: `./gradlew :builder:run`
    };

    tabBtns.forEach(btn => {
      btn.addEventListener('click', (e) => {
        tabBtns.forEach(b => b.classList.remove('active'));
        e.target.classList.add('active');

        const targetCmd = e.target.getAttribute('data-tab');
        codeContent.innerText = commandTexts[targetCmd];
      });
    });
  }

  // --- EULA Language Toggle ---
  const eulaKoBtn = document.getElementById('eula-ko-btn');
  const eulaEnBtn = document.getElementById('eula-en-btn');
  const eulaKoContent = document.getElementById('eula-ko-content');
  const eulaEnContent = document.getElementById('eula-en-content');

  if (eulaKoBtn && eulaEnBtn && eulaKoContent && eulaEnContent) {
    eulaKoBtn.addEventListener('click', () => {
      eulaKoBtn.classList.add('active');
      eulaEnBtn.classList.remove('active');
      eulaKoContent.style.display = 'block';
      eulaEnContent.style.display = 'none';
    });

    eulaEnBtn.addEventListener('click', () => {
      eulaEnBtn.classList.add('active');
      eulaKoBtn.classList.remove('active');
      eulaKoContent.style.display = 'none';
      eulaEnContent.style.display = 'block';
    });
  }

  // --- Interactive Effects ---
  const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  const isFinePointer = window.matchMedia('(hover: hover) and (pointer: fine)').matches;

  // Click / Tap Star Burst
  if (!prefersReducedMotion) {
    const burstColors = ['#ff6b9d', '#a370f7', '#3bcef2', '#ffd166'];
    const burstGlyphs = ['★', '✦', '✧'];

    const spawnStarBurst = (x, y) => {
      const ripple = document.createElement('div');
      ripple.className = 'click-ripple';
      ripple.style.left = `${x}px`;
      ripple.style.top = `${y}px`;
      document.body.appendChild(ripple);
      ripple.addEventListener('animationend', () => ripple.remove());

      const count = 7 + Math.floor(Math.random() * 4);
      for (let i = 0; i < count; i++) {
        const star = document.createElement('span');
        star.className = 'click-star';
        star.textContent = burstGlyphs[Math.floor(Math.random() * burstGlyphs.length)];
        star.style.color = burstColors[Math.floor(Math.random() * burstColors.length)];
        star.style.left = `${x}px`;
        star.style.top = `${y}px`;
        star.style.fontSize = `${10 + Math.random() * 14}px`;

        const angle = (Math.PI * 2 * i) / count + (Math.random() - 0.5) * 0.6;
        const distance = 40 + Math.random() * 60;
        star.style.setProperty('--tx', `${Math.cos(angle) * distance}px`);
        star.style.setProperty('--ty', `${Math.sin(angle) * distance}px`);
        star.style.setProperty('--r', `${(Math.random() - 0.5) * 360}deg`);
        star.style.setProperty('--s', `${0.6 + Math.random() * 0.8}`);

        document.body.appendChild(star);
        star.addEventListener('animationend', () => star.remove());
      }
    };

    document.addEventListener('pointerdown', (e) => {
      spawnStarBurst(e.clientX, e.clientY);
    });
  }

  // Cursor Glow Trail (desktop / fine pointer only)
  if (!prefersReducedMotion && isFinePointer) {
    const glow = document.createElement('div');
    glow.className = 'cursor-glow';
    document.body.appendChild(glow);

    let targetX = window.innerWidth / 2;
    let targetY = window.innerHeight / 2;
    let currentX = targetX;
    let currentY = targetY;

    window.addEventListener('mousemove', (e) => {
      targetX = e.clientX;
      targetY = e.clientY;
      glow.classList.add('active');
    });

    document.addEventListener('mouseleave', () => glow.classList.remove('active'));

    (function animateGlow() {
      currentX += (targetX - currentX) * 0.12;
      currentY += (targetY - currentY) * 0.12;
      glow.style.transform = `translate(${currentX}px, ${currentY}px) translate(-50%, -50%)`;
      requestAnimationFrame(animateGlow);
    })();
  }

  // Hover Tilt + Spotlight Reveal on cards / rows
  if (!prefersReducedMotion && isFinePointer) {
    document.querySelectorAll('.card').forEach((card) => {
      card.addEventListener('mousemove', (e) => {
        const rect = card.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const y = e.clientY - rect.top;
        const rotateX = ((y - rect.height / 2) / rect.height) * -10;
        const rotateY = ((x - rect.width / 2) / rect.width) * 10;
        card.style.transform = `perspective(800px) rotateX(${rotateX}deg) rotateY(${rotateY}deg) translateY(-6px)`;
        card.style.setProperty('--mx', `${(x / rect.width) * 100}%`);
        card.style.setProperty('--my', `${(y / rect.height) * 100}%`);
      });
      card.addEventListener('mouseleave', () => {
        card.style.transform = '';
      });
    });

    document.querySelectorAll('.module-row, .guide-step, .disclaimer-item').forEach((el) => {
      el.addEventListener('mousemove', (e) => {
        const rect = el.getBoundingClientRect();
        const x = e.clientX - rect.left;
        const y = e.clientY - rect.top;
        el.style.setProperty('--mx', `${(x / rect.width) * 100}%`);
        el.style.setProperty('--my', `${(y / rect.height) * 100}%`);
      });
    });
  }
});
