import { vi } from 'vitest';
import type { Address, Book, Notification, Order, OrderStatus, Topic, User } from '../src/types';

/**
 * An in-memory stand-in for user-service, catalog-service and order-service.
 * Tests render the real app; every fetch('/api/...') is answered from here.
 */

export const CUSTOMER: User = { id: 7, name: 'Asha Kumar', email: 'asha@example.com', phone: null, role: 'CUSTOMER' };
export const ADMIN: User = { id: 1, name: 'Store Admin', email: 'admin@example.com', phone: null, role: 'ADMIN' };

/** A token the app can read (the services check the signature; the browser only reads the payload). */
export function tokenFor(user: User): string {
  const part = (o: object) => btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
  const exp = Math.floor(Date.now() / 1000) + 3600;
  return `${part({ alg: 'HS256' })}.${part({ sub: String(user.id), email: user.email, name: user.name, role: user.role, exp })}.sig`;
}

const book = (b: Partial<Book> & Pick<Book, 'id' | 'title' | 'topic' | 'logo'>): Book => ({
  subtitle: 'A practical guide', color1: '#326CE5', color2: '#12206b', mrp: 99900, price: 59900, stock: 10,
  pages: 300, highlights: ['Hands-on labs'], imageUrl: null, active: true, ...b,
});

export function seedBooks(): Book[] {
  return [
    book({ id: 9, title: 'Kubernetes Field Guide', topic: 'Containers', logo: 'k8s', mrp: 119900, price: 69900 }),
    book({ id: 8, title: 'Docker in Production', topic: 'Containers', logo: 'docker', stock: 3 }),
    book({ id: 4, title: 'Jenkins CI/CD', topic: 'CI/CD', logo: 'jenkins', stock: 0 }),
    book({ id: 500, title: 'Terraform Recipes', subtitle: 'Modules and state', topic: 'IaC', logo: 'terraform',
      mrp: 30000, price: 30000, highlights: ['Remote state', 'Reusable modules'], imageUrl: 'https://example.com/tf.png' }),
    book({ id: 501, title: 'Hidden Book', topic: 'IaC', logo: 'ansible', active: false }),
  ];
}

export const TOPICS: Topic[] = [
  { key: 'Containers', name: 'Containers and orchestration', description: 'Package it. Run it.', books: 2 },
  { key: 'CI/CD', name: 'CI/CD and GitOps', description: 'From commit to live.', books: 1 },
  { key: 'IaC', name: 'Infrastructure as code', description: 'Servers from files.', books: 1 },
  { key: 'Cloud', name: 'Cloud platforms', description: 'AWS and friends.', books: 0 },
];

export function order(o: Partial<Order> & Pick<Order, 'id' | 'number' | 'status'>): Order {
  return {
    userId: CUSTOMER.id, customerName: CUSTOMER.name, customerEmail: CUSTOMER.email, paymentMethod: 'COD',
    paymentStatus: 'COD_DUE', itemsTotal: 139800, mrpTotal: 239800, delivery: 0, total: 139800,
    address: { name: 'Asha Kumar', mobile: '9876543210', line1: '12 MG Road', city: 'Pune', state: 'Maharashtra', pincode: '411001' },
    createdAt: '2026-11-20T08:00:00Z',
    items: [{ bookId: 9, title: 'Kubernetes Field Guide', qty: 2, price: 69900, mrp: 119900 }],
    events: [{ status: 'PLACED', note: 'Order placed', at: '2026-11-20T08:00:00Z' }],
    ...o,
  };
}

export interface Backend {
  books: Book[];
  orders: Order[];
  addresses: Address[];
  wishlist: number[];
  notifications: Notification[];
  online: boolean;
  /** Next call to this path fails with this status, code and message. */
  failures: Record<string, { status: number; code: string; message: string }>;
  requests: { method: string; path: string; body: unknown }[];
}

const json = (status: number, body: unknown) =>
  new Response(body === undefined ? null : JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

export function startBackend(): Backend {
  const db: Backend = {
    books: seedBooks(), orders: [], addresses: [], wishlist: [], notifications: [], online: false, failures: {}, requests: [],
  };
  let next = 100001;

  const session = (u: User) => ({ token: tokenFor(u), user: u });
  const placed = (o: Order) => ({
    order: o,
    payment: o.status === 'PAYMENT_PENDING' ? { keyId: 'rzp_test_key', razorpayOrderId: `order_rzp_${o.id}`, amount: o.total } : null,
  });

  function answer(method: string, path: string, body: any): Response {
    const fail = db.failures[`${method} ${path}`];
    if (fail) {
      delete db.failures[`${method} ${path}`];
      return json(fail.status, { code: fail.code, message: fail.message });
    }
    const route = `${method} ${path.replace(/\?.*$/, '')}`;
    let m: RegExpMatchArray | null;

    // ---------- user-service ----------
    if (route === 'POST /api/auth/login') return json(200, session(body.email === ADMIN.email ? ADMIN : CUSTOMER));
    if (route === 'POST /api/auth/register') return json(202, { email: body.email, message: `We sent a 6-digit code to ${body.email}` });
    if (route === 'POST /api/auth/verify') return json(200, session(CUSTOMER));
    if (route === 'POST /api/auth/resend-code') return json(202, { message: 'A new code is on its way' });
    if (route === 'POST /api/auth/forgot-password') return json(202, { message: `A reset code is on its way to ${body.email}` });
    if (route === 'POST /api/auth/reset-password') return json(200, session(CUSTOMER));
    if (route === 'GET /api/users/me/addresses') return json(200, db.addresses);
    if (route === 'POST /api/users/me/addresses') {
      const a = { ...body, id: db.addresses.length + 1 };
      db.addresses.push(a);
      return json(201, a);
    }

    // ---------- catalog-service ----------
    if (route === 'GET /api/topics') return json(200, TOPICS);
    if (route === 'GET /api/books') return json(200, db.books.filter((b) => b.active));
    if (route === 'GET /api/wishlist') return json(200, db.wishlist);
    if ((m = route.match(/^(PUT|DELETE) \/api\/wishlist\/(\d+)$/))) {
      const id = Number(m[2]);
      db.wishlist = m[1] === 'PUT' ? [...new Set([...db.wishlist, id])] : db.wishlist.filter((x) => x !== id);
      return json(204, undefined);
    }

    // ---------- order-service ----------
    if (route === 'GET /api/payments/options') return json(200, { cod: true, online: db.online });
    if (route === 'POST /api/orders') {
      const online = body.paymentMethod === 'RAZORPAY';
      const items = body.items.map((i: { bookId: number; qty: number }) => {
        const b = db.books.find((x) => x.id === i.bookId)!;
        return { bookId: b.id, title: b.title, qty: i.qty, price: b.price, mrp: b.mrp };
      });
      const total = items.reduce((s: number, i: { price: number; qty: number }) => s + i.price * i.qty, 0);
      const o = order({
        id: `order-${next}`, number: `PGM-${next++}`, status: online ? 'PAYMENT_PENDING' : 'PLACED',
        paymentMethod: online ? 'RAZORPAY' : 'COD', paymentStatus: online ? 'PENDING' : 'COD_DUE',
        items, itemsTotal: total, total, address: body.address,
      });
      db.orders.unshift(o);
      return json(201, placed(o));
    }
    if (route === 'GET /api/orders') return json(200, db.orders);
    if ((m = route.match(/^POST \/api\/orders\/([\w-]+)\/cancel$/))) return json(200, setStatus(m[1], 'CANCELLED'));
    if ((m = route.match(/^GET \/api\/orders\/([\w-]+)\/payment$/))) {
      return json(200, placed(db.orders.find((o) => o.id === m![1])!).payment);
    }
    if ((m = route.match(/^POST \/api\/orders\/([\w-]+)\/payment$/))) {
      const o = setStatus(m[1], 'PLACED');
      o.paymentStatus = 'PAID';
      return json(200, o);
    }
    if (route === 'GET /api/notifications') return json(200, db.notifications);
    if (route === 'POST /api/notifications/read') {
      db.notifications = db.notifications.map((n) => ({ ...n, read: true }));
      return json(204, undefined);
    }
    if (route === 'DELETE /api/notifications') {
      db.notifications = [];
      return json(204, undefined);
    }

    // ---------- admin ----------
    if (route === 'GET /api/admin/books') return json(200, db.books);
    if (route === 'GET /api/admin/books/stats') {
      return json(200, { books: db.books.filter((b) => b.active).length, lowStock: db.books.filter((b) => b.stock <= 5).length });
    }
    if (route === 'POST /api/admin/books') {
      const b = { ...body, id: 100 + db.books.length };
      db.books.push(b);
      return json(201, b);
    }
    if ((m = route.match(/^PUT \/api\/admin\/books\/(\d+)$/))) return json(200, updateBook(Number(m[1]), body));
    if ((m = route.match(/^PATCH \/api\/admin\/books\/(\d+)\/(stock|active)$/))) return json(200, updateBook(Number(m[1]), body));
    if (route === 'GET /api/admin/orders') {
      const status = new URLSearchParams(path.split('?')[1] ?? '').get('status');
      return json(200, status ? db.orders.filter((o) => o.status === status) : db.orders);
    }
    if (route === 'GET /api/admin/orders/stats') {
      const byStatus: Record<string, number> = {};
      db.orders.forEach((o) => (byStatus[o.status] = (byStatus[o.status] ?? 0) + 1));
      return json(200, { orders: db.orders.length, revenue: db.orders.reduce((s, o) => s + o.total, 0), byStatus });
    }
    if ((m = route.match(/^PATCH \/api\/admin\/orders\/([\w-]+)\/status$/))) return json(200, setStatus(m[1], body.status));

    return json(404, { code: 'NOT_FOUND', message: `No route ${route}` });
  }

  function setStatus(id: string, status: OrderStatus): Order {
    const o = db.orders.find((x) => x.id === id)!;
    o.status = status;
    o.events = [...o.events, { status, note: status, at: '2026-11-20T09:00:00Z' }];
    return o;
  }

  function updateBook(id: number, changes: Partial<Book>): Book {
    const i = db.books.findIndex((b) => b.id === id);
    db.books[i] = { ...db.books[i], ...changes };
    return db.books[i];
  }

  vi.stubGlobal('fetch', vi.fn(async (path: string, init: RequestInit = {}) => {
    const method = init.method ?? 'GET';
    const body = init.body ? JSON.parse(String(init.body)) : undefined;
    db.requests.push({ method, path, body });
    return answer(method, path, body);
  }));
  return db;
}
